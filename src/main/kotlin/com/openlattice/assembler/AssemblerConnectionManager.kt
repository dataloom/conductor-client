/*
 * Copyright (C) 2019. OpenLattice, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the owner of the copyright at support@openlattice.com
 *
 *
 */

package com.openlattice.assembler

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.MetricRegistry.name
import com.codahale.metrics.Timer
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import com.openlattice.assembler.PostgresDatabases.Companion.buildOrganizationDatabaseName
import com.openlattice.assembler.PostgresRoles.Companion.buildOrganizationRoleName
import com.openlattice.assembler.PostgresRoles.Companion.buildOrganizationUserId
import com.openlattice.assembler.PostgresRoles.Companion.buildPostgresRoleName
import com.openlattice.assembler.PostgresRoles.Companion.buildPostgresUsername
import com.openlattice.authorization.*
import com.openlattice.directory.MaterializedViewAccount
import com.openlattice.edm.EntitySet
import com.openlattice.edm.type.PropertyType
import com.openlattice.organization.OrganizationEntitySetFlag
import com.openlattice.organization.roles.Role
import com.openlattice.organizations.HazelcastOrganizationService
import com.openlattice.organizations.roles.SecurePrincipalsManager
import com.openlattice.postgres.DataTables.quote
import com.openlattice.postgres.PostgresColumn.*
import com.openlattice.postgres.PostgresTable.E
import com.openlattice.postgres.PostgresTable.PRINCIPALS
import com.openlattice.postgres.ResultSetAdapters
import com.openlattice.postgres.streams.PostgresIterable
import com.openlattice.postgres.streams.StatementHolder
import com.openlattice.principals.RoleCreatedEvent
import com.openlattice.principals.UserCreatedEvent
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.Statement
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.Function
import java.util.function.Supplier
import kotlin.NoSuchElementException

private val logger = LoggerFactory.getLogger(AssemblerConnectionManager::class.java)

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@Component
class AssemblerConnectionManager(
        private val assemblerConfiguration: AssemblerConfiguration,
        private val hds: HikariDataSource,
        private val securePrincipalsManager: SecurePrincipalsManager,
        private val organizations: HazelcastOrganizationService,
        private val dbCredentialService: DbCredentialService,
        eventBus: EventBus,
        metricRegistry: MetricRegistry
) {

    private val perDbCache: LoadingCache<String, HikariDataSource> = CacheBuilder
            .newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build(cacheLoader())
    private val target: HikariDataSource = connect("postgres")
    private val materializeAllTimer: Timer =
            metricRegistry.timer(name(AssemblerConnectionManager::class.java, "materializeAll"))
    private val materializeEntitySetsTimer: Timer =
            metricRegistry.timer(name(AssemblerConnectionManager::class.java, "materializeEntitySets"))
    private val materializeEdgesTimer: Timer =
            metricRegistry.timer(name(AssemblerConnectionManager::class.java, "materializeEdges"))

    init {
        eventBus.register(this)
    }

    companion object {
        const val PUBLIC_ROLE = "public"

        @JvmStatic
        val INTEGRATIONS_SCHEMA = "integrations"

        @JvmStatic
        val MATERIALIZED_VIEWS_SCHEMA = "openlattice"

        @JvmStatic
        val PUBLIC_SCHEMA = "public"

        @JvmStatic
        val STAGING_SCHEMA = "staging"

        @JvmStatic
        fun entitySetNameTableName(entitySetName: String): String {
            return "$MATERIALIZED_VIEWS_SCHEMA.${quote(entitySetName)}"
        }

        @JvmStatic
        fun createDataSource(dbName: String, config: Properties, useSsl: Boolean): HikariDataSource {
            config.computeIfPresent("jdbcUrl") { _, jdbcUrl ->
                "${(jdbcUrl as String).removeSuffix(
                        "/"
                )}/$dbName" + if (useSsl) {
                    "?sslmode=require"
                } else {
                    ""
                }
            }
            return HikariDataSource(HikariConfig(config))
        }
    }

    fun cacheLoader(): CacheLoader<String, HikariDataSource> {
        return CacheLoader.from { dbName ->
            createDataSource(dbName!!, assemblerConfiguration.server.clone() as Properties, assemblerConfiguration.ssl)
        }
    }

    fun connect(dbName: String): HikariDataSource {
        return perDbCache.get(dbName)
    }

    @Deprecated(
            message = "doesn't use the connection pool cache",
            replaceWith = ReplaceWith(expression = "#connect(String)")
    )
    fun connect(dbName: String, account: MaterializedViewAccount): HikariDataSource {
        val config = assemblerConfiguration.server.clone() as Properties
        config["username"] = account.username
        config["password"] = account.credential

        return createDataSource(dbName, config, assemblerConfiguration.ssl)
    }

    @Subscribe
    fun handleUserCreated(userCreatedEvent: UserCreatedEvent) {
        createUnprivilegedUser(userCreatedEvent.user)
    }

    @Subscribe
    fun handleRoleCreated(roleCreatedEvent: RoleCreatedEvent) {
        createRole(roleCreatedEvent.role)
    }

    /**
     * Creates a private organization database that can be used for uploading data using launchpad.
     * Also sets up foreign data wrapper using assembler in assembler so that materialized views of data can be
     * provided.
     */
    fun createOrganizationDatabase(organizationId: UUID) {
        logger.info("Creating organization database for organization with id $organizationId")
        val organization = organizations.getOrganization(organizationId)!!
        val dbName = buildOrganizationDatabaseName(organizationId)
        createOrganizationDatabase(organizationId, dbName)

        connect(dbName).let { dataSource ->
            configureRolesInDatabase(dataSource)
            createSchema(dataSource, MATERIALIZED_VIEWS_SCHEMA)
            createSchema(dataSource, INTEGRATIONS_SCHEMA)
            createSchema(dataSource, STAGING_SCHEMA)
            configureOrganizationUser(organizationId, dataSource)
            addMembersToOrganization(dbName, dataSource, organization.members)
            configureServerUser(dataSource)
        }
    }

    private fun createSchema(dataSource: HikariDataSource, schemaName: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA IF NOT EXISTS $schemaName")
            }
        }
    }

    private fun configureServerUser(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                        setSearchPathSql(
                                assemblerConfiguration.server["username"].toString(),
                                false,
                                INTEGRATIONS_SCHEMA,
                                MATERIALIZED_VIEWS_SCHEMA,
                                PUBLIC_SCHEMA,
                                STAGING_SCHEMA
                        )
                )
            }
        }
    }

    private fun configureOrganizationUser(organizationId: UUID, dataSource: HikariDataSource) {
        val dbOrgUser = quote(dbCredentialService.getDbUsername(buildOrganizationUserId(organizationId)))
        dataSource.connection.createStatement().use { statement ->
            //Allow usage and create on schema openlattice to organization user
            statement.execute(grantOrgUserPrivilegesOnSchemaSql(MATERIALIZED_VIEWS_SCHEMA, dbOrgUser))
            statement.execute(grantOrgUserPrivilegesOnSchemaSql(STAGING_SCHEMA, dbOrgUser))
            statement.execute(setSearchPathSql(dbOrgUser, true, MATERIALIZED_VIEWS_SCHEMA, STAGING_SCHEMA))
        }
    }

    fun addMembersToOrganization(dbName: String, dataSource: HikariDataSource, members: Set<Principal>) {
        logger.info("Configuring members for organization database {}", dbName)
        val validUserPrincipals = members
                .filter {
                    it.id != SystemRole.OPENLATTICE.principal.id && it.id != SystemRole.ADMIN.principal.id
                }
                .filter {
                    val principalExists = securePrincipalsManager.principalExists(it)
                    if (!principalExists) {
                        logger.warn("Principal {} does not exists", it)
                    }
                    return@filter principalExists
                } //There are some bad principals in the member list some how-- probably from testing.

        val securablePrincipalsToAdd = securePrincipalsManager.getSecurablePrincipals(validUserPrincipals)
        if (securablePrincipalsToAdd.isNotEmpty()) {
            val userNames = securablePrincipalsToAdd.map { dbCredentialService.getDbUsername(buildPostgresUsername(it)) }
            configureUsersInDatabase(dataSource, dbName, userNames)
        }
    }

    fun addMembersToOrganization(
            dbName: String,
            dataSource: HikariDataSource,
            authorizedPropertyTypesOfEntitySetsByPrincipal: Map<SecurablePrincipal, Map<EntitySet, Collection<PropertyType>>>
    ) {
        if (authorizedPropertyTypesOfEntitySetsByPrincipal.isNotEmpty()) {
            val authorizedPropertyTypesOfEntitySetsByPostgresUser = authorizedPropertyTypesOfEntitySetsByPrincipal
                    .mapKeys { dbCredentialService.getDbUsername(buildPostgresUsername(it.key)) }
            val userNames = authorizedPropertyTypesOfEntitySetsByPostgresUser.keys
            configureUsersInDatabase(dataSource, dbName, userNames)
            dataSource.connection.use { connection ->
                grantSelectForNewMembers(connection, authorizedPropertyTypesOfEntitySetsByPostgresUser)
            }
        }
    }

    fun removeMembersFromOrganization(
            dbName: String,
            dataSource: HikariDataSource,
            principals: Collection<SecurablePrincipal>
    ) {
        if (principals.isNotEmpty()) {
            val userNames = principals.map { dbCredentialService.getDbUsername(buildPostgresUsername(it)) }
            revokeConnectAndSchemaUsage(dataSource, dbName, userNames)
        }
    }

    fun updateCredentialInDatabase(organizationId: UUID, userId: String, credential: String) {
        val updateSql = updateUserCredentialSql(userId, credential)

        connect(buildOrganizationDatabaseName(organizationId)).connection.use { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute(updateSql)
            }
        }
    }

    private fun createOrganizationDatabase(organizationId: UUID, dbName: String) {
        val db = quote(dbName)
        val dbRole = buildOrganizationRoleName(dbName)

        val unquotedDbAdminUser = buildOrganizationUserId(organizationId)

        val (dbOrgUser, dbAdminUserPassword) = dbCredentialService.getOrCreateUserCredentials(unquotedDbAdminUser)

        val createOrgDbRole = createRoleIfNotExistsSql(dbRole)
        val createOrgDbUser = createUserIfNotExistsSql(unquotedDbAdminUser, dbAdminUserPassword)

        val grantRole = "GRANT ${quote(dbRole)} TO ${quote(dbOrgUser)}"
        val createDb = "CREATE DATABASE $db"
        val revokeAll = "REVOKE ALL ON DATABASE $db FROM $PUBLIC_ROLE"

        //We connect to default db in order to do initial db setup

        target.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(createOrgDbRole)
                statement.execute(createOrgDbUser)
                statement.execute(grantRole)
                if (!exists(dbName)) {
                    statement.execute(createDb)
                    statement.execute(
                            "GRANT ${MEMBER_ORG_DATABASE_PERMISSIONS.joinToString(", ")} " +
                                    "ON DATABASE $db TO ${quote(dbOrgUser)}"
                    )
                }
                statement.execute(revokeAll)
                return@use
            }
        }
    }

    fun dropOrganizationDatabase(organizationId: UUID) {
        dropOrganizationDatabase(organizationId, buildOrganizationDatabaseName(organizationId))
    }

    fun dropOrganizationDatabase(organizationId: UUID, dbName: String) {
        val db = quote(dbName)
        val dbRole = quote(buildOrganizationRoleName(dbName))
        val unquotedDbAdminUser = buildOrganizationUserId(organizationId)
        val dbAdminUser = dbCredentialService.getDbUsername(unquotedDbAdminUser)

        val dropDb = " DROP DATABASE $db"
        val dropDbUser = "DROP ROLE $dbAdminUser"
        //TODO: If we grant this role to other users, we need to make sure we drop it
        val dropDbRole = "DROP ROLE $dbRole"


        //We connect to default db in order to do initial db setup

        target.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(dropDb)
                statement.execute(dropDbUser)
                statement.execute(dropDbRole)
                return@use
            }
        }
    }

    fun materializeEntitySets(
            organizationId: UUID,
            authorizedPropertyTypesByEntitySet: Map<EntitySet, Map<UUID, PropertyType>>,
            authorizedPropertyTypesOfPrincipalsByEntitySetId: Map<UUID, Map<Principal, Set<PropertyType>>>
    ): Map<UUID, Set<OrganizationEntitySetFlag>> {
        logger.info(
                "Materializing entity sets ${authorizedPropertyTypesByEntitySet.keys.map { it.id }} in " +
                        "organization $organizationId database."
        )

        materializeAllTimer.time().use {
            connect(buildOrganizationDatabaseName(organizationId)).let { datasource ->
                materializeEntitySets(
                        datasource,
                        authorizedPropertyTypesByEntitySet,
                        authorizedPropertyTypesOfPrincipalsByEntitySetId
                )
            }
            return authorizedPropertyTypesByEntitySet
                    .map { it.key.id to EnumSet.of(OrganizationEntitySetFlag.MATERIALIZED) }
                    .toMap()
        }
    }


    private fun materializeEntitySets(
            dataSource: HikariDataSource,
            materializablePropertyTypesByEntitySet: Map<EntitySet, Map<UUID, PropertyType>>,
            authorizedPropertyTypesOfPrincipalsByEntitySetId: Map<UUID, Map<Principal, Set<PropertyType>>>
    ) {
        materializablePropertyTypesByEntitySet.forEach { (entitySet, materializablePropertyTypes) ->
            materialize(
                    dataSource,
                    entitySet,
                    materializablePropertyTypes,
                    authorizedPropertyTypesOfPrincipalsByEntitySetId.getValue(entitySet.id)
            )
        }
    }

    /**
     * Materializes an entity set on atlas.
     */
    private fun materialize(
            dataSource: HikariDataSource,
            entitySet: EntitySet,
            materializablePropertyTypes: Map<UUID, PropertyType>,
            authorizedPropertyTypesOfPrincipals: Map<Principal, Set<PropertyType>>
    ) {
        materializeEntitySetsTimer.time().use {
            val tableName = entitySetNameTableName(entitySet.name)

            dataSource.connection.use { connection ->
                // first drop and create materialized view
                logger.info("Materialized entity set ${entitySet.id}")

                //Next we need to grant select on materialize view to everyone who has permission.
                val selectGrantedResults = grantSelectForEntitySet(
                        connection,
                        tableName,
                        entitySet.id,
                        authorizedPropertyTypesOfPrincipals
                )
                logger.info(
                        "Granted select for ${selectGrantedResults.filter { it >= 0 }.size} users/roles " +
                                "on materialized view $tableName"
                )
            }
        }
    }

    private fun getSelectColumnsForMaterializedView(propertyTypes: Collection<PropertyType>): List<String> {
        return listOf(ENTITY_SET_ID.name, ID_VALUE.name, ENTITY_KEY_IDS_COL.name) + propertyTypes.map {
            quote(it.type.fullQualifiedNameAsString)
        }
    }

    private fun grantSelectForEntitySet(
            connection: Connection,
            tableName: String,
            entitySetId: UUID,
            authorizedPropertyTypesOfPrincipals: Map<Principal, Set<PropertyType>>
    ): IntArray {
        // prepare batch queries
        return connection.createStatement().use { stmt ->
            authorizedPropertyTypesOfPrincipals.forEach { (principal, propertyTypes) ->
                val columns = getSelectColumnsForMaterializedView(propertyTypes)
                try {
                    val grantSelectSql = grantSelectSql(tableName, principal, columns)
                    stmt.addBatch(grantSelectSql)
                } catch (e: NoSuchElementException) {
                    logger.error("Principal $principal does not exists but has permission on entity set $entitySetId")
                }
            }
            stmt.executeBatch()
        }
    }

    fun grantSelectForEdges(
            stmt: Statement, tableName: String, entitySetIds: Set<UUID>, authorizedPrincipals: Set<Principal>
    ): IntArray {
        authorizedPrincipals.forEach {
            try {
                val grantSelectSql = grantSelectSql(tableName, it, listOf())
                stmt.addBatch(grantSelectSql)
            } catch (e: NoSuchElementException) {
                logger.error("Principal $it does not exists but has permission on one of the entity sets $entitySetIds")
            }
        }

        return stmt.executeBatch()
    }

    private fun grantSelectForNewMembers(
            connection: Connection,
            authorizedPropertyTypesOfEntitySetsByPostgresUser: Map<String, Map<EntitySet, Collection<PropertyType>>>
    ): IntArray {
        // prepare batch queries
        return connection.createStatement().use { stmt ->
            authorizedPropertyTypesOfEntitySetsByPostgresUser
                    .forEach { (postgresUserName, authorizedPropertyTypesOfEntitySets) ->

                        // grant select on authorized tables and their properties
                        authorizedPropertyTypesOfEntitySets.forEach { (entitySet, propertyTypes) ->
                            val tableName = entitySetNameTableName(entitySet.name)
                            val columns = getSelectColumnsForMaterializedView(propertyTypes)
                            val grantSelectSql = grantSelectSql(tableName, postgresUserName, columns)
                            stmt.addBatch(grantSelectSql)
                        }

                        // also grant select on edges (if at least 1 entity set is materialized to make sure edges
                        // materialized view exist)
                        if (authorizedPropertyTypesOfEntitySets.isNotEmpty()) {
                            val edgesTableName = "$MATERIALIZED_VIEWS_SCHEMA.${E.name}"
                            val grantSelectSql = grantSelectSql(edgesTableName, postgresUserName, listOf())
                            stmt.addBatch(grantSelectSql)
                        }
                    }
            stmt.executeBatch()
        }
    }

    /**
     * Build grant select sql statement for a given table and principal with column level security.
     * If properties (columns) are left empty, it will grant select on whole table.
     */
    @Throws(NoSuchElementException::class)
    private fun grantSelectSql(
            entitySetTableName: String,
            principal: Principal,
            columns: List<String>
    ): String {
        val postgresUserName = when (principal.type) {
            PrincipalType.USER -> dbCredentialService.getDbUsername(buildPostgresUsername(securePrincipalsManager.getPrincipal(principal.id)))
            PrincipalType.ROLE -> buildPostgresRoleName(securePrincipalsManager.lookupRole(principal))
            else -> throw IllegalArgumentException(
                    "Only ${PrincipalType.USER} and ${PrincipalType.ROLE} principal " +
                            "types can be granted select."
            )
        }

        return grantSelectSql(entitySetTableName, quote(postgresUserName), columns)
    }

    /**
     * Build grant select sql statement for a given table and user with column level security.
     * If properties (columns) are left empty, it will grant select on whole table.
     */
    private fun grantSelectSql(
            tableName: String,
            postgresUserName: String,
            columns: List<String>
    ): String {
        val onProperties = if (columns.isEmpty()) {
            ""
        } else {
            "( ${columns.joinToString(",")} )"
        }

        return "GRANT SELECT $onProperties " +
                "ON $tableName " +
                "TO $postgresUserName"
    }

    /**
     * Synchronize data changes in entity set materialized view in organization database.
     */
    fun refreshEntitySet(organizationId: UUID, entitySet: EntitySet) {
        logger.info("Refreshing entity set ${entitySet.id} in organization $organizationId database")
        val tableName = entitySetNameTableName(entitySet.name)

        connect(buildOrganizationDatabaseName(organizationId)).let { dataSource ->
            dataSource.connection.use { connection ->
                connection.createStatement().use {
                    it.execute("REFRESH MATERIALIZED VIEW $tableName")
                }
            }
        }
    }

    /**
     * Renames a materialized view in the requested organization.
     * @param organizationId The id of the organization in which the entity set is materialized and should be renamed.
     * @param newName The new name of the entity set.
     * @param oldName The old name of the entity set.
     */
    fun renameMaterializedEntitySet(organizationId: UUID, newName: String, oldName: String) {
        connect(buildOrganizationDatabaseName(organizationId)).let { dataSource ->
            dataSource.connection.createStatement().use { stmt ->
                val newTableName = quote(newName)
                val oldTableName = entitySetNameTableName(oldName)

                stmt.executeUpdate("ALTER MATERIALIZED VIEW IF EXISTS $oldTableName RENAME TO $newTableName")
            }
        }
        logger.info(
                "Renamed materialized view of entity set with old name $oldName to new name $newName in " +
                        "organization $organizationId"
        )
    }

    /**
     * Removes a materialized entity set from atlas.
     */
    fun dematerializeEntitySets(organizationId: UUID, entitySetIds: Set<UUID>) {
        val dbName = buildOrganizationDatabaseName(organizationId)
        connect(dbName).let { dataSource ->
            //TODO: Implement de-materialization code here.
        }
        logger.info("Removed materialized entity sets $entitySetIds from organization $organizationId")
    }

    internal fun exists(dbName: String): Boolean {
        target.connection.use { connection ->
            connection.createStatement().use { stmt ->
                stmt.executeQuery("select count(*) from pg_database where datname = '$dbName'").use { rs ->
                    rs.next()
                    return rs.getInt("count") > 0
                }
            }
        }
    }

    fun getAllRoles(): PostgresIterable<Role> {
        return PostgresIterable(
                Supplier {
                    val conn = hds.connection
                    val ps = conn.prepareStatement(PRINCIPALS_SQL)
                    ps.setString(1, PrincipalType.ROLE.name)
                    StatementHolder(conn, ps, ps.executeQuery())
                },
                Function { securePrincipalsManager.getSecurablePrincipal(ResultSetAdapters.aclKey(it)) as Role }
        )
    }

    fun getAllUsers(): PostgresIterable<SecurablePrincipal> {
        return PostgresIterable(
                Supplier {
                    val conn = hds.connection
                    val ps = conn.prepareStatement(PRINCIPALS_SQL)
                    ps.setString(1, PrincipalType.USER.name)
                    StatementHolder(conn, ps, ps.executeQuery())
                },
                Function { securePrincipalsManager.getSecurablePrincipal(ResultSetAdapters.aclKey(it)) }
        )
    }


    private fun configureRolesInDatabase(dataSource: HikariDataSource) {
        val roles = getAllRoles()
        if (roles.iterator().hasNext()) {
            val roleIds = roles.map { quote(buildPostgresRoleName(it)) }
            val roleIdsSql = roleIds.joinToString(",")

            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    logger.info("Revoking $PUBLIC_SCHEMA schema right from roles: {}", roleIds)
                    //Don't allow users to access public schema which will contain foreign data wrapper tables.
                    statement.execute("REVOKE USAGE ON SCHEMA $PUBLIC_SCHEMA FROM $roleIdsSql")
                }
            }
        }
    }

    fun createRole(role: Role) {
        val dbRole = buildPostgresRoleName(role)

        target.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(createRoleIfNotExistsSql(dbRole))
                //Don't allow users to access public schema which will contain foreign data wrapper tables.
                logger.info("Revoking $PUBLIC_SCHEMA schema right from role: {}", role)
                statement.execute("REVOKE USAGE ON SCHEMA $PUBLIC_SCHEMA FROM ${quote(dbRole)}")

                return@use
            }
        }
    }

    fun createUnprivilegedUser(user: SecurablePrincipal) {
        val dbUserKey = buildPostgresUsername(user)

        /**
         * To simplify work-around for ESRI username limitations, we are only introducing one additional
         * field into the dbcreds table. We keep the results of calling [buildPostgresUsername] as the lookup
         * key, but instead use the username and password returned from the db credential service.
         */
        val (dbUser, dbUserPassword) = dbCredentialService.getOrCreateUserCredentials(dbUserKey)

        target.connection.use { connection ->
            connection.createStatement().use { statement ->
                //TODO: Go through every database and for old users clean them out.
//                    logger.info("Attempting to drop owned by old name {}", user.name)
//                    statement.execute(dropOwnedIfExistsSql(user.name))
//                    logger.info("Attempting to drop user {}", user.name)
//                    statement.execute(dropUserIfExistsSql(user.name)) //Clean out the old users.
//                    logger.info("Creating new user {}", dbUser)
                logger.info("Creating user if not exists {}", dbUser)
                statement.execute(createUserIfNotExistsSql(dbUser, dbUserPassword))
                //Don't allow users to access public schema which will contain foreign data wrapper tables.
                logger.info("Revoking $PUBLIC_SCHEMA schema right from user {}", user)
                statement.execute("REVOKE USAGE ON SCHEMA $PUBLIC_SCHEMA FROM ${quote(dbUser)}")

                return@use
            }
        }
    }

    private fun configureUsersInDatabase(dataSource: HikariDataSource, dbName: String, userIds: Collection<String>) {
        val userIdsSql = userIds.joinToString(", ")

        logger.info("Configuring users $userIds in database $dbName")
        //First we will grant all privilege which for database is connect, temporary, and create schema
        target.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                        "GRANT ${MEMBER_ORG_DATABASE_PERMISSIONS.joinToString(", ")} " +
                                "ON DATABASE ${quote(dbName)} TO $userIdsSql"
                )
            }
        }

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                logger.info(
                        "Granting USAGE on {} schema, and granting USAGE and CREATE on {} schema for users: {}",
                        MATERIALIZED_VIEWS_SCHEMA,
                        STAGING_SCHEMA,
                        PUBLIC_SCHEMA,
                        userIds
                )
                statement.execute("GRANT USAGE ON SCHEMA $MATERIALIZED_VIEWS_SCHEMA TO $userIdsSql")
                statement.execute("GRANT USAGE, CREATE ON SCHEMA $STAGING_SCHEMA TO $userIdsSql")
                //Set the search path for the user
                logger.info("Setting search_path to $MATERIALIZED_VIEWS_SCHEMA for users $userIds")
                userIds.forEach { userId ->
                    statement.addBatch(setSearchPathSql(userId, true, MATERIALIZED_VIEWS_SCHEMA, STAGING_SCHEMA))
                }
                statement.executeBatch()
            }
        }
    }

    private fun revokeConnectAndSchemaUsage(dataSource: HikariDataSource, dbName: String, userIds: List<String>) {
        val userIdsSql = userIds.joinToString(", ")

        logger.info(
                "Removing users $userIds from database $dbName, schema usage and all privileges on all tables in schemas {} and {}",
                MATERIALIZED_VIEWS_SCHEMA,
                STAGING_SCHEMA
        )

        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(revokePrivilegesOnDatabaseSql(dbName, userIdsSql))

                stmt.execute(revokePrivilegesOnSchemaSql(MATERIALIZED_VIEWS_SCHEMA, userIdsSql))
                stmt.execute(revokePrivilegesOnTablesInSchemaSql(MATERIALIZED_VIEWS_SCHEMA, userIdsSql))

                stmt.execute(revokePrivilegesOnSchemaSql(STAGING_SCHEMA, userIdsSql))
                stmt.execute(revokePrivilegesOnTablesInSchemaSql(STAGING_SCHEMA, userIdsSql))
            }
        }
    }
}

val MEMBER_ORG_DATABASE_PERMISSIONS = setOf("CREATE", "CONNECT", "TEMPORARY", "TEMP")


private val PRINCIPALS_SQL = "SELECT ${ACL_KEY.name} FROM ${PRINCIPALS.name} WHERE ${PRINCIPAL_TYPE.name} = ?"

private fun grantOrgUserPrivilegesOnSchemaSql(schemaName: String, orgUserId: String): String {
    return "GRANT USAGE, CREATE ON SCHEMA $schemaName TO $orgUserId"
}

private fun setSearchPathSql(granteeId: String, isUser: Boolean, vararg schemas: String): String {
    val schemasSql = schemas.joinToString()
    val granteeType = if (isUser) "USER" else "ROLE"
    return "ALTER $granteeType $granteeId SET search_path TO $schemasSql"
}

private fun revokePrivilegesOnDatabaseSql(dbName: String, usersSql: String): String {
    return "REVOKE ${MEMBER_ORG_DATABASE_PERMISSIONS.joinToString(", ")} ON DATABASE ${quote(dbName)} FROM $usersSql"
}

private fun revokePrivilegesOnSchemaSql(schemaName: String, usersSql: String): String {
    return "REVOKE ALL PRIVILEGES ON SCHEMA $schemaName FROM $usersSql"
}

private fun revokePrivilegesOnTablesInSchemaSql(schemaName: String, usersSql: String): String {
    return "REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA $schemaName FROM $usersSql"
}

internal fun createRoleIfNotExistsSql(dbRole: String): String {
    return "DO\n" +
            "\$do\$\n" +
            "BEGIN\n" +
            "   IF NOT EXISTS (\n" +
            "      SELECT\n" +
            "      FROM   pg_catalog.pg_roles\n" +
            "      WHERE  rolname = '$dbRole') THEN\n" +
            "\n" +
            "      CREATE ROLE ${quote(
                    dbRole
            )} NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOLOGIN;\n" +
            "   END IF;\n" +
            "END\n" +
            "\$do\$;"
}

internal fun createUserIfNotExistsSql(dbUser: String, dbUserPassword: String): String {
    return "DO\n" +
            "\$do\$\n" +
            "BEGIN\n" +
            "   IF NOT EXISTS (\n" +
            "      SELECT\n" +
            "      FROM   pg_catalog.pg_roles\n" +
            "      WHERE  rolname = '$dbUser') THEN\n" +
            "\n" +
            "      CREATE ROLE ${quote(
                    dbUser
            )} NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT LOGIN ENCRYPTED PASSWORD '$dbUserPassword';\n" +
            "   END IF;\n" +
            "END\n" +
            "\$do\$;"
}

internal fun dropOwnedIfExistsSql(dbUser: String): String {
    return "DO\n" +
            "\$do\$\n" +
            "BEGIN\n" +
            "   IF EXISTS (\n" +
            "      SELECT\n" +
            "      FROM   pg_catalog.pg_roles\n" +
            "      WHERE  rolname = '$dbUser') THEN\n" +
            "\n" +
            "      DROP OWNED BY ${quote(
                    dbUser
            )} ;\n" +
            "   END IF;\n" +
            "END\n" +
            "\$do\$;"
}

internal fun updateUserCredentialSql(dbUser: String, credential: String): String {
    return "ALTER ROLE $dbUser WITH ENCRYPTED PASSWORD '$credential'"
}

internal fun dropUserIfExistsSql(dbUser: String): String {
    return "DO\n" +
            "\$do\$\n" +
            "BEGIN\n" +
            "   IF EXISTS (\n" +
            "      SELECT\n" +
            "      FROM   pg_catalog.pg_roles\n" +
            "      WHERE  rolname = '$dbUser') THEN\n" +
            "\n" +
            "      DROP ROLE ${quote(
                    dbUser
            )} ;\n" +
            "   END IF;\n" +
            "END\n" +
            "\$do\$;"
}


