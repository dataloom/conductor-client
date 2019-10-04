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
import com.google.common.collect.Sets
import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.query.Predicate
import com.hazelcast.query.Predicates
import com.hazelcast.query.QueryConstants
import com.openlattice.IdConstants
import com.openlattice.assembler.PostgresRoles.Companion.buildOrganizationUserId
import com.openlattice.assembler.events.MaterializedEntitySetDataChangeEvent
import com.openlattice.assembler.events.MaterializedEntitySetEdmChangeEvent
import com.openlattice.assembler.processors.*
import com.openlattice.authorization.*
import com.openlattice.authorization.securable.SecurableObjectType
import com.openlattice.controllers.exceptions.ResourceNotFoundException
import com.openlattice.data.storage.partitions.PartitionManager
import com.openlattice.data.storage.selectPropertyTypesOfEntitySetColumnar
import com.openlattice.datastore.util.Util
import com.openlattice.edm.EntitySet
import com.openlattice.edm.events.*
import com.openlattice.edm.type.EntityType
import com.openlattice.edm.type.PropertyType
import com.openlattice.hazelcast.HazelcastMap.*
import com.openlattice.organization.Organization
import com.openlattice.organization.OrganizationEntitySetFlag
import com.openlattice.organization.OrganizationIntegrationAccount
import com.openlattice.organizations.events.MembersAddedToOrganizationEvent
import com.openlattice.organizations.events.MembersRemovedFromOrganizationEvent
import com.openlattice.organizations.roles.SecurePrincipalsManager
import com.openlattice.organizations.tasks.OrganizationsInitializationTask
import com.openlattice.postgres.DataTables
import com.openlattice.postgres.mapstores.MaterializedEntitySetMapStore
import com.openlattice.postgres.mapstores.OrganizationAssemblyMapstore
import com.openlattice.postgres.mapstores.OrganizationAssemblyMapstore.INITIALIZED_INDEX
import com.openlattice.tasks.HazelcastInitializationTask
import com.openlattice.tasks.HazelcastTaskDependencies
import com.openlattice.tasks.PostConstructInitializerTaskDependencies.PostConstructInitializerTask
import com.openlattice.tasks.Task
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.util.*
import java.util.stream.Collectors
import kotlin.streams.toList

private val logger = LoggerFactory.getLogger(Assembler::class.java)

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
class Assembler(
        private val dbCredentialService: DbCredentialService,
        val hds: HikariDataSource,
        private val authorizationManager: AuthorizationManager,
        private val edmAuthorizationHelper: EdmAuthorizationHelper,
        private val securePrincipalsManager: SecurePrincipalsManager,
        private val partitionManager: PartitionManager,
        metricRegistry: MetricRegistry,
        hazelcast: HazelcastInstance,
        eventBus: EventBus

) : HazelcastTaskDependencies, AssemblerConnectionManagerDependent<Void?> {

    private val entitySets = hazelcast.getMap<UUID, EntitySet>(ENTITY_SETS.name)
    private val entityTypes = hazelcast.getMap<UUID, EntityType>(ENTITY_TYPES.name)
    private val propertyTypes = hazelcast.getMap<UUID, PropertyType>(PROPERTY_TYPES.name)
    private val assemblies = hazelcast.getMap<UUID, OrganizationAssembly>(ASSEMBLIES.name)
    private val materializedEntitySets = hazelcast
            .getMap<EntitySetAssemblyKey, MaterializedEntitySet>(MATERIALIZED_ENTITY_SETS.name)
    private val securableObjectTypes = hazelcast.getMap<AclKey, SecurableObjectType>(SECURABLE_OBJECT_TYPES.name)
    private val principals = hazelcast.getMap<AclKey, SecurablePrincipal>(PRINCIPALS.name)

    private val createOrganizationTimer = metricRegistry.timer(name(Assembler::class.java, "createOrganization"))
    private val deleteOrganizationTimer = metricRegistry.timer(name(Assembler::class.java, "deleteOrganization"))

    private lateinit var acm: AssemblerConnectionManager

    init {
        eventBus.register(this)
    }

    override fun init(acm: AssemblerConnectionManager): Void? {
        this.acm = acm
        return null
    }

    fun getMaterializedEntitySetsInOrganization(organizationId: UUID): Map<UUID, Set<OrganizationEntitySetFlag>> {
        return assemblies[organizationId]?.materializedEntitySets ?: mapOf()
    }

    fun getMaterializedEntitySetIdsInOrganization(organizationId: UUID): Set<UUID> {
        return assemblies[organizationId]?.materializedEntitySets?.keys ?: setOf()
    }

    @Subscribe
    fun handleEntitySetDataChange(entitySetDataChangeEvent: MaterializedEntitySetDataChangeEvent) {
        flagMaterializedEntitySet(entitySetDataChangeEvent.entitySetId, OrganizationEntitySetFlag.DATA_UNSYNCHRONIZED)
    }

    @Subscribe
    fun handleEntitySetEdmChange(entitySetEdmChangeEvent: MaterializedEntitySetEdmChangeEvent) {
        flagMaterializedEntitySet(entitySetEdmChangeEvent.entitySetId, OrganizationEntitySetFlag.EDM_UNSYNCHRONIZED)
    }

    private fun flagMaterializedEntitySet(entitySetId: UUID, flag: OrganizationEntitySetFlag) {
        if (isEntitySetMaterialized(entitySetId)) {
            materializedEntitySets.executeOnEntries(
                    AddFlagsToMaterializedEntitySetProcessor(setOf(flag)),
                    entitySetIdPredicate(entitySetId)
            )
            assemblies.executeOnEntries(
                    AddFlagsToOrganizationMaterializedEntitySetProcessor(entitySetId, setOf(flag)),
                    entitySetIdInOrganizationPredicate(entitySetId)
            )
        }
    }

    /**
     * Updates the refresh rate for a materialized entity set.
     */
    fun updateRefreshRate(organizationId: UUID, entitySetId: UUID, refreshRate: Long?) {
        val entitySetAssemblyKey = EntitySetAssemblyKey(entitySetId, organizationId)

        ensureEntitySetIsMaterialized(entitySetAssemblyKey)

        materializedEntitySets.executeOnKey(entitySetAssemblyKey, UpdateRefreshRateProcessor(refreshRate))
    }

    @Subscribe
    fun handleEntitySetCreated(entitySetCreatedEvent: EntitySetCreatedEvent) {
        createOrUpdateProductionViewOfEntitySet(entitySetCreatedEvent.entitySet.id)
    }

    @Subscribe
    fun handleEntitySetDeleted(entitySetDeletedEvent: EntitySetDeletedEvent) {
        // when entity set is deleted, we drop it's view from both openlattice and organization databases and update
        // entity_sets and edges table in organization databases
        if (isEntitySetMaterialized(entitySetDeletedEvent.entitySetId)) {
            logger.info("Removing materialized entity set ${entitySetDeletedEvent.entitySetId} from all " +
                    "organizations because of entity set deletion")

            val entitySetAssembliesToDelete = materializedEntitySets.keySet(
                    entitySetIdPredicate(entitySetDeletedEvent.entitySetId)
            )
            deleteEntitySetAssemblies(entitySetAssembliesToDelete)
        }

        dropProductionViewOfEntitySet(entitySetDeletedEvent.entitySetId)
    }

    @Subscribe
    fun handleEntitySetOrganizationUpdated(entitySetOrganizationUpdatedEvent: EntitySetOrganizationUpdatedEvent) {
        val entitySetAssemblyKey = EntitySetAssemblyKey(
                entitySetOrganizationUpdatedEvent.entitySetId,
                entitySetOrganizationUpdatedEvent.oldOrganizationId
        )
        if (isEntitySetMaterialized(entitySetAssemblyKey)) {
            logger.info("Removing materialized entity set ${entitySetOrganizationUpdatedEvent.entitySetId}  from " +
                    "organization ${entitySetOrganizationUpdatedEvent.oldOrganizationId} because of organization update")
            // when an entity set is moved to a new organization, we need to delete its assembly from old organization
            deleteEntitySetAssemblies(setOf(entitySetAssemblyKey))
        }
    }

    private fun deleteEntitySetAssemblies(entitySetAssemblies: Set<EntitySetAssemblyKey>) {
        materializedEntitySets.executeOnKeys(
                entitySetAssemblies,
                DropMaterializedEntitySetProcessor().init(acm)
        )

        // also remove entries from assemblies entity sets and re-materialize edges
        entitySetAssemblies
                .groupBy { it.organizationId }
                .mapValues { it.value.map(EntitySetAssemblyKey::entitySetId) }
                .forEach { (organizationId, entitySetIds) ->
                    materializeEdges(organizationId)

                    assemblies.executeOnKey(
                            organizationId,
                            RemoveMaterializedEntitySetsFromOrganizationProcessor(entitySetIds)
                    )
                }
    }

    @Subscribe
    fun handleEntitySetNameUpdated(entitySetNameUpdatedEvent: EntitySetNameUpdatedEvent) {
        materializedEntitySets.executeOnEntries(
                RenameMaterializedEntitySetProcessor(
                        entitySetNameUpdatedEvent.newName, entitySetNameUpdatedEvent.oldName
                ).init(acm),
                entitySetIdPredicate(entitySetNameUpdatedEvent.entitySetId)
        )
    }

    @Subscribe
    fun handlePropertyTypeAddedToEntitySet(propertyTypesAddedToEntitySetEvent: PropertyTypesAddedToEntitySetEvent) {
        // when property type is added to entity set, we need to update (re-create) the production view (olviews) of the
        // entity set in openlattice db
        if (isEntitySetMaterialized(propertyTypesAddedToEntitySetEvent.entitySet.id)) {
            createOrUpdateProductionViewOfEntitySet(propertyTypesAddedToEntitySetEvent.entitySet.id)
        }
    }

    @Subscribe
    fun handlePropertyTypeFqnChanged(propertyTypesInEntitySetUpdatedEvent: PropertyTypesInEntitySetUpdatedEvent) {
        // when property type fqn changes, we need to update (re-create) the production view (olviews) of the entity set
        // in openlattice db
        if (propertyTypesInEntitySetUpdatedEvent.fqnUpdated
                && isEntitySetMaterialized(propertyTypesInEntitySetUpdatedEvent.entitySetId)) {
            createOrUpdateProductionViewOfEntitySet(propertyTypesInEntitySetUpdatedEvent.entitySetId)
        }
    }

    @Subscribe
    fun handleLinkedEntitySetAdded(linkedEntitySetAddedEvent: LinkedEntitySetAddedEvent) {
        // when normal entity set is added to linking entity set, we need to update (re-create) the production view
        // (olviews) of the entity set in openlattice db
        if (isEntitySetMaterialized(linkedEntitySetAddedEvent.linkingEntitySetId)) {
            createOrUpdateProductionViewOfEntitySet(linkedEntitySetAddedEvent.linkingEntitySetId)
        }
    }

    @Subscribe
    fun handleLinkedEntitySetRemoved(linkedEntitySetRemovedEvent: LinkedEntitySetRemovedEvent) {
        // when normal entity set is removed from linking entity set, we need to update (re-create) the production view
        // (olviews) of the entity set in openlattice db
        if (isEntitySetMaterialized(linkedEntitySetRemovedEvent.linkingEntitySetId)) {
            createOrUpdateProductionViewOfEntitySet(linkedEntitySetRemovedEvent.linkingEntitySetId)
        }
    }


    fun createOrganization(organization: Organization) {
        createOrganization(organization.id)
    }

    fun createOrganization(organizationId: UUID) {
        createOrganizationTimer.time().use {
            assemblies.set(organizationId, OrganizationAssembly(organizationId))
            assemblies.executeOnKey(organizationId, InitializeOrganizationAssemblyProcessor().init(acm))
            return@use
        }
    }

    fun destroyOrganization(organizationId: UUID) {
        deleteOrganizationTimer.time().use {
            assemblies.executeOnKey(organizationId, DeleteOrganizationAssemblyProcessor().init(acm))
            Util.deleteSafely(assemblies, organizationId)
            materializedEntitySets.removeAll(organizationIdPredicate(organizationId))
        }
    }

    @Subscribe
    fun handleAddMembersToOrganization(event: MembersAddedToOrganizationEvent) {
        // check if organization is initialized
        ensureAssemblyInitialized(event.organizationId)

        val authorizedPropertyTypesOfEntitySetsByNewMembers = event.newMembers.associateWith { principal ->
            // we also grant select on entity sets, where no property type is authorized
            val authorizedEntitySets = authorizationManager.getAuthorizedObjectsOfType(
                    principal.principal,
                    SecurableObjectType.EntitySet,
                    EdmAuthorizationHelper.READ_PERMISSION
            )
            val authorizedPropertyTypeAcls = authorizationManager.getAuthorizedObjectsOfType(
                    principal.principal,
                    SecurableObjectType.PropertyTypeInEntitySet,
                    EdmAuthorizationHelper.READ_PERMISSION
            )

            val allEntitySets = entitySets
                    .getAll(authorizedEntitySets.map { it[0] }.collect(Collectors.toSet()))
                    .filter { isEntitySetMaterialized(EntitySetAssemblyKey(it.key, event.organizationId)) }
            val authorizedPropertyTypesByEntitySets = authorizedPropertyTypeAcls.toList()
                    .groupBy { it[0] }
                    .map { it.key to it.value.map { it[1] } }
                    .toMap()

            allEntitySets.values
                    .associateWith {
                        if (authorizedPropertyTypesByEntitySets.containsKey(it.id)) {
                            propertyTypes.getAll(authorizedPropertyTypesByEntitySets.getValue(it.id).toSet()).values
                        } else {
                            listOf<PropertyType>()
                        }
                    }
        }

        assemblies.executeOnKey(
                event.organizationId,
                AddMembersToOrganizationAssemblyProcessor(authorizedPropertyTypesOfEntitySetsByNewMembers).init(acm)
        )
    }

    @Subscribe
    fun removeMembersFromOrganization(event: MembersRemovedFromOrganizationEvent) {
        // check if organization is initialized
        ensureAssemblyInitialized(event.organizationId)

        assemblies.executeOnKey(
                event.organizationId,
                RemoveMembersFromOrganizationAssemblyProcessor(event.members).init(acm)
        )
    }

    fun materializeEntitySets(
            organizationId: UUID,
            authorizedPropertyTypesByEntitySet: Map<UUID, Map<UUID, PropertyType>>,
            refreshRatesOfEntitySets: Map<UUID, Long>
    ): Map<UUID, Set<OrganizationEntitySetFlag>> {
        logger.info("Materializing entity sets ${authorizedPropertyTypesByEntitySet.keys}")

        // check if organization is initialized
        ensureAssemblyInitialized(organizationId)

        // materialize entity sets
        authorizedPropertyTypesByEntitySet.forEach { (entitySetId, materializablePropertyTypes) ->
            // even if we re-materialize, we would clear all flags
            val materializedEntitySetKey = EntitySetAssemblyKey(entitySetId, organizationId)
            val entitySet = entitySets.getValue(entitySetId)
            val authorizedPropertyTypesOfPrincipals =
                    getAuthorizedPropertiesOfPrincipals(entitySet, materializablePropertyTypes)

            materializedEntitySets.set(
                    materializedEntitySetKey,
                    MaterializedEntitySet(materializedEntitySetKey, refreshRatesOfEntitySets.getValue(entitySetId)))
            materializedEntitySets.executeOnKey(
                    materializedEntitySetKey,
                    MaterializeEntitySetProcessor(
                            entitySet, materializablePropertyTypes, authorizedPropertyTypesOfPrincipals
                    ).init(acm)
            )
        }

        // update assemblies mapstore with new materialized entity sets
        assemblies.executeOnKey(
                organizationId,
                AddMaterializedEntitySetsToOrganizationProcessor(authorizedPropertyTypesByEntitySet.keys)
        )

        materializeEdges(organizationId)

        return getMaterializedEntitySetIdsInOrganization(organizationId).map {
            it to (setOf(OrganizationEntitySetFlag.MATERIALIZED) + getInternalEntitySetFlag(organizationId, it))
        }.toMap()
    }

    private fun materializeEdges(organizationId: UUID) {
        // materialize edges by including all the materialized entity sets in organization
        val entitySetIds = assemblies.getValue(organizationId).materializedEntitySets.keys
        val authorizedPrincipals = getAuthorizedPrincipalsForEdges(entitySetIds)
        assemblies.executeOnKey(organizationId, MaterializeEdgesProcessor(authorizedPrincipals).init(acm))
    }

    private fun getAuthorizedPropertiesOfPrincipals(
            entitySet: EntitySet,
            materializablePropertyTypes: Map<UUID, PropertyType>
    ): Map<Principal, Set<PropertyType>> {
        // collect all principals of type user, role, which have read access on entityset
        val authorizedPrincipals = getReadAuthorizedUsersAndRolesOnEntitySet(entitySet.id)

        val propertyCheckFunction: (Principal) -> (Map<UUID, PropertyType>) = { principal ->
            // only grant select on authorized columns if principal has read access on every normal entity set
            // within the linking entity set
            if (entitySet.isLinking &&
                    !entitySet.linkedEntitySets.all {
                        authorizationManager.checkIfHasPermissions(
                                AclKey(it),
                                setOf(principal),
                                EdmAuthorizationHelper.READ_PERMISSION)
                    }
            ) {
                mapOf()
            } else {
                edmAuthorizationHelper.getAuthorizedPropertyTypes(
                        entitySet.id,
                        EdmAuthorizationHelper.READ_PERMISSION,
                        materializablePropertyTypes,
                        setOf(principal)
                )
            }
        }

        // collect all authorized property types for principals which have read access on entity set
        return authorizedPrincipals
                .map { it to propertyCheckFunction(it).values.toSet() }
                .toMap()
    }

    private fun getAuthorizedPrincipalsForEdges(entitySetIds: Set<UUID>): Set<Principal> {
        // collect all principals of type user, role, which have read access on entityset
        return entitySetIds.fold(mutableSetOf()) { acc, entitySetId ->
            Sets.union(acc, getReadAuthorizedUsersAndRolesOnEntitySet(entitySetId).toSet())
        }
    }

    private fun getReadAuthorizedUsersAndRolesOnEntitySet(entitySetId: UUID): List<Principal> {
        return securePrincipalsManager
                .getAuthorizedPrincipalsOnSecurableObject(AclKey(entitySetId), EdmAuthorizationHelper.READ_PERMISSION)
                .filter { it.type == PrincipalType.USER || it.type == PrincipalType.ROLE }
    }

    fun synchronizeMaterializedEntitySet(
            organizationId: UUID,
            entitySetId: UUID,
            materializablePropertyTypes: Map<UUID, PropertyType>
    ) {
        val entitySetAssemblyKey = EntitySetAssemblyKey(entitySetId, organizationId)

        ensureAssemblyInitialized(organizationId)
        ensureEntitySetIsMaterialized(entitySetAssemblyKey)

        // check if it's not already in sync
        if (materializedEntitySets[entitySetAssemblyKey]!!.flags
                        .contains(OrganizationEntitySetFlag.EDM_UNSYNCHRONIZED)) {
            logger.info("Synchronizing materialized entity set $entitySetId")

            val entitySet = entitySets.getValue(entitySetId)
            val authorizedPropertyTypesOfPrincipals =
                    getAuthorizedPropertiesOfPrincipals(entitySet, materializablePropertyTypes)

            materializedEntitySets.executeOnKey(
                    entitySetAssemblyKey,
                    SynchronizeMaterializedEntitySetProcessor(
                            entitySet, materializablePropertyTypes, authorizedPropertyTypesOfPrincipals
                    ).init(acm)
            )

            // remove flag also from organization entity sets
            assemblies.executeOnKey(
                    organizationId,
                    RemoveFlagsFromOrganizationMaterializedEntitySetProcessor(
                            entitySetId,
                            EnumSet.of(
                                    OrganizationEntitySetFlag.EDM_UNSYNCHRONIZED,
                                    OrganizationEntitySetFlag.DATA_UNSYNCHRONIZED
                            )
                    )
            )

            materializeEdges(organizationId)
        }
    }

    fun refreshMaterializedEntitySet(
            organizationId: UUID, entitySetId: UUID, materializablePropertyTypes: Map<UUID, PropertyType>
    ) {
        val entitySetAssemblyKey = EntitySetAssemblyKey(entitySetId, organizationId)

        ensureAssemblyInitialized(organizationId)
        ensureEntitySetIsMaterialized(entitySetAssemblyKey)

        // Only allow refresh if edm is in sync and data is not already refreshed
        if (!materializedEntitySets[entitySetAssemblyKey]!!.flags.contains(OrganizationEntitySetFlag.EDM_UNSYNCHRONIZED)
                && materializedEntitySets[entitySetAssemblyKey]!!.flags.contains(
                        OrganizationEntitySetFlag.DATA_UNSYNCHRONIZED
                )) {
            logger.info("Refreshing materialized entity set $entitySetId")

            val entitySet = entitySets.getValue(entitySetId)
            val authorizedPropertyTypesOfPrincipals =
                    getAuthorizedPropertiesOfPrincipals(entitySet, materializablePropertyTypes)

            materializedEntitySets.executeOnKey(
                    entitySetAssemblyKey,
                    RefreshMaterializedEntitySetProcessor(
                            entitySet, materializablePropertyTypes, authorizedPropertyTypesOfPrincipals
                    ).init(acm)
            )
            // remove flag also from organization entity sets
            assemblies.executeOnKey(
                    organizationId,
                    RemoveFlagsFromOrganizationMaterializedEntitySetProcessor(
                            entitySetId, EnumSet.of(OrganizationEntitySetFlag.DATA_UNSYNCHRONIZED)
                    )
            )

            materializeEdges(organizationId)
        }
    }

    private fun createOrUpdateProductionViewOfEntitySet(entitySetId: UUID) {
        val entitySet = entitySets.getValue(entitySetId)
        val entitySetPartitions = partitionManager.getEntitySetPartitionsInfo(entitySetId).partitions
        val authorizedPropertyTypes = propertyTypes
                .getAll(entityTypes.getValue(entitySets.getValue(entitySetId).entityTypeId).properties)
                .filter { it.key != IdConstants.ID_ID.id } //filter out @id

        val selectPropertiesSql = selectPropertyTypesOfEntitySetColumnar(authorizedPropertyTypes, entitySet.isLinking)
        // Since the sql is a preparable statement, but create statements don't allow them, we need to replace ?
        val entitySetIdClause = if (entitySet.isLinking) " '{${entitySet.linkedEntitySets.joinToString()}}' " else " '{$entitySetId}' "
        val partitionsClause = " '{${entitySetPartitions.joinToString()}}' "
        val preparedSelectPropertiesSql = selectPropertiesSql
                .replaceFirst("?", entitySetIdClause)
                .replaceFirst("?", partitionsClause)

        val entitySetViewName = entitySetViewName(entitySetId)

        //Drop and recreate the view with the latest schema
        hds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP VIEW IF EXISTS $entitySetViewName")
                stmt.execute("CREATE OR REPLACE VIEW $entitySetViewName AS $preparedSelectPropertiesSql")
            }
        }

        logger.info("Created or updated view of $entitySetId in ${AssemblerConnectionManager.PRODUCTION_VIEWS_SCHEMA}")
    }

    private fun dropProductionViewOfEntitySet(entitySetId: UUID) {
        hds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP VIEW IF EXISTS ${entitySetViewName(entitySetId)}")
            }
        }
        logger.info("Dropped view of entity set  $entitySetId in ${AssemblerConnectionManager.PRODUCTION_VIEWS_SCHEMA}")
    }

    private fun entitySetViewName(entitySetId: UUID): String {
        return "${AssemblerConnectionManager.PRODUCTION_VIEWS_SCHEMA}.${DataTables.quote(entitySetId.toString())}"
    }

    fun getOrganizationIntegrationAccount(organizationId: UUID): OrganizationIntegrationAccount {
        val organizationUserId = buildOrganizationUserId(organizationId)
        val credential = this.dbCredentialService.getDbCredential(organizationUserId)
                ?: throw ResourceNotFoundException("Organization credential not found.")
        return OrganizationIntegrationAccount(organizationUserId, credential)
    }

    private fun getInternalEntitySetFlag(organizationId: UUID, entitySetId: UUID): Set<OrganizationEntitySetFlag> {
        return if (entitySets[entitySetId]?.organizationId == organizationId) {
            setOf(OrganizationEntitySetFlag.INTERNAL)
        } else {
            setOf()
        }
    }

    /**
     * Returns true, if the entity set is materialized in any of the organization assemblies
     */
    private fun isEntitySetMaterialized(entitySetId: UUID): Boolean {
        return materializedEntitySets
                .keySet(entitySetIdPredicate(entitySetId))
                .isNotEmpty()
    }

    /**
     * Returns true, if the entity set is materialized in the organization
     */
    private fun isEntitySetMaterialized(entitySetAssemblyKey: EntitySetAssemblyKey): Boolean {
        return materializedEntitySets.keySet(entitySetAssemblyKeyPredicate(entitySetAssemblyKey)).isNotEmpty()
    }

    private fun ensureEntitySetIsMaterialized(entitySetAssemblyKey: EntitySetAssemblyKey) {
        if (!isEntitySetMaterialized(entitySetAssemblyKey)) {
            throw IllegalStateException("Entity set ${entitySetAssemblyKey.entitySetId} is not materialized for " +
                    "organization ${entitySetAssemblyKey.organizationId}")
        }
    }

    private fun ensureAssemblyInitialized(organizationId: UUID) {
        val isAssemblyInitialized = assemblies
                .executeOnKey(organizationId, IsAssemblyInitializedEntryProcessor()) as Boolean
        if (!isAssemblyInitialized) {
            throw IllegalStateException("Organization assembly is not initialized for organization $organizationId")
        }
    }

    private fun entitySetIdPredicate(entitySetId: UUID): Predicate<*, *> {
        return Predicates.equal(MaterializedEntitySetMapStore.ENTITY_SET_ID_INDEX, entitySetId)
    }

    @Suppress("UNCHECKED_CAST")
    private fun organizationIdPredicate(entitySetId: UUID): Predicate<EntitySetAssemblyKey, MaterializedEntitySet> {
        return Predicates.equal(MaterializedEntitySetMapStore.ORGANIZATION_ID_INDEX, entitySetId)
                as Predicate<EntitySetAssemblyKey, MaterializedEntitySet>
    }

    private fun entitySetIdInOrganizationPredicate(entitySetId: UUID): Predicate<*, *> {
        return Predicates.equal(OrganizationAssemblyMapstore.MATERIALIZED_ENTITY_SETS_ID_INDEX, entitySetId)
    }

    private fun entitySetAssemblyKeyPredicate(entitySetAssemblyKey: EntitySetAssemblyKey): Predicate<*, *> {
        return Predicates.equal(QueryConstants.KEY_ATTRIBUTE_NAME.value(), entitySetAssemblyKey)
    }


    /**
     * This class is responsible for refreshing all entity set views at startup.
     */
    class EntitySetViewsInitializerTask : HazelcastInitializationTask<Assembler> {
        override fun getInitialDelay(): Long {
            return 0
        }

        override fun initialize(dependencies: Assembler) {
            dependencies.entitySets.keys.forEach(dependencies::createOrUpdateProductionViewOfEntitySet)
        }

        override fun after(): Set<Class<out HazelcastInitializationTask<*>>> {
            return setOf(OrganizationsInitializationTask::class.java)
        }

        override fun getName(): String {
            return Task.ENTITY_VIEWS_INITIALIZER.name
        }

        override fun getDependenciesClass(): Class<out Assembler> {
            return Assembler::class.java
        }
    }


    class OrganizationAssembliesInitializerTask : HazelcastInitializationTask<Assembler> {
        override fun getInitialDelay(): Long {
            return 0
        }

        override fun initialize(dependencies: Assembler) {
            val currentOrganizations =
                    dependencies
                            .securableObjectTypes.keySet(
                            Predicates.equal(
                                    "this"
                                    , SecurableObjectType.Organization
                            )
                    )
                            .map { it.first() }
                            .toSet()

            val initializedOrganizations = dependencies.assemblies.keySet(Predicates.equal(INITIALIZED_INDEX, true))

            val organizationsNeedingInitialized: Set<UUID> = currentOrganizations - initializedOrganizations

            organizationsNeedingInitialized.forEach { organizationId ->
                val organizationPrincipal = dependencies.principals[AclKey(organizationId)]
                if (organizationPrincipal == null) {
                    logger.error(
                            "Unable to initialize organization with id {} because principal not found", organizationId
                    )
                } else {
                    logger.info("Initializing database for organization {}", organizationId)
                    dependencies.createOrganization(organizationId)
                }
            }
        }

        override fun after(): Set<Class<out HazelcastInitializationTask<*>>> {
            return setOf(
                    EntitySetViewsInitializerTask::class.java,
                    PostConstructInitializerTask::class.java
            )
        }

        override fun getName(): String {
            return Task.ORGANIZATION_ASSEMBLIES_INITIALIZER.name
        }

        override fun getDependenciesClass(): Class<out Assembler> {
            return Assembler::class.java
        }
    }
}


