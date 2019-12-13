/*
 * Copyright (C) 2018. OpenLattice, Inc.
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

package com.openlattice.authorization.mapstores

import com.codahale.metrics.annotation.Timed
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSortedSet
import com.hazelcast.config.*
import com.kryptnostic.rhizome.mapstores.TestableSelfRegisteringMapStore
import com.openlattice.authorization.AclKey
import com.openlattice.authorization.AclKeySet
import com.openlattice.hazelcast.HazelcastMap
import com.openlattice.mapstores.TestDataFactory
import com.openlattice.postgres.PostgresColumn.ACL_KEY
import com.openlattice.postgres.PostgresColumn.PRINCIPAL_OF_ACL_KEY
import com.openlattice.postgres.PostgresTable.PRINCIPAL_TREES
import com.openlattice.postgres.ResultSetAdapters
import com.openlattice.postgres.streams.PostgresIterable
import com.openlattice.postgres.streams.StatementHolder
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.util.*
import java.util.function.Function
import java.util.function.Supplier

/**
 *
 * Quick and dirty mapstore for principal trees.
 */

private val logger = LoggerFactory.getLogger(PrincipalTreesMapstore::class.java)!!

@Service //This is here to allow this class to be automatically open for @Timed to work correctly
class PrincipalTreesMapstore(val hds: HikariDataSource) : TestableSelfRegisteringMapStore<AclKey, AclKeySet> {
    @Timed
    override fun storeAll(map: Map<AclKey, AclKeySet>) {
        hds.connection.use {
            val connection = it
            val stmt = connection.createStatement()

            stmt.use {
                map.forEach { entry ->
                    val aclKey = entry.key
                    val filterPrincipal = if (entry.value.isEmpty()) {
                        ""
                    } else {
                        " AND ${PRINCIPAL_OF_ACL_KEY.name} NOT IN (" + entry.value.joinToString(",") { key ->
                            toPostgres(key)
                        } + ")"
                    }
                    val sql = "DELETE from ${PRINCIPAL_TREES.name} " +
                            "WHERE ${ACL_KEY.name} = ${toPostgres(entry.key)} $filterPrincipal"
                    stmt.addBatch(sql)
                    entry.value.forEach {
                        stmt.addBatch(
                                "INSERT INTO ${PRINCIPAL_TREES.name} " +
                                        "VALUES (${toPostgres(aclKey)}, ${toPostgres(
                                                it
                                        )}) ON CONFLICT DO NOTHING"
                        )
                    }
                }
                stmt.executeBatch()
            }
        }
    }

    fun toPostgres(aclKey: AclKey): String {
        return "'{\"" + aclKey.joinToString("\",\"") + "\"}'::uuid[]"
    }

    override fun loadAllKeys(): Iterable<AclKey> {
        return PostgresIterable<AclKey>(Supplier {
            logger.info("Load all iterator requested for ${this.mapName}")
            val connection = hds.connection
            val stmt = connection.createStatement()
            val rs = stmt.executeQuery("SELECT distinct(${ACL_KEY.name}) from ${PRINCIPAL_TREES.name}")

            StatementHolder(connection, stmt, rs)
        }, Function<ResultSet, AclKey> { ResultSetAdapters.aclKey(it) }
        )
    }

    @Timed
    override fun store(key: AclKey, value: AclKeySet) {
        storeAll(mapOf(key to value))
    }

    @Timed
    override fun loadAll(keys: Collection<AclKey>): Map<AclKey, AclKeySet> {
        val data = PostgresIterable<Pair<AclKey, AclKey>>(
                Supplier {
                    val connection = hds.connection
                    val stmt = connection.createStatement()

                    val sql = "SELECT * from ${PRINCIPAL_TREES.name} " +
                            "WHERE ${ACL_KEY.name} " +
                            "IN (" + keys.joinToString(",") { toPostgres(it) } + ")"

                    StatementHolder(connection, stmt, stmt.executeQuery(sql))
                },

                Function<ResultSet, Pair<AclKey, AclKey>> {
                    ResultSetAdapters.aclKey(it) to ResultSetAdapters.principalOfAclKey(it)
                }
        )
        val map = mutableMapOf<AclKey, AclKeySet>()
        data.forEach { map.getOrPut(it.first) { AclKeySet() }.add(it.second) }
        ( keys - map.keys ).forEach { map[it] = AclKeySet() }

        return map
    }

    @Timed
    override fun deleteAll(keys: Collection<AclKey>) {
        hds.connection.use { connection ->
            connection.createStatement().use { stmt ->
                val sql = "DELETE from ${PRINCIPAL_TREES.name} " +
                        "WHERE ${ACL_KEY.name} " +
                        "IN (" + keys.joinToString(",") { toPostgres(it) } + ")"

                stmt.executeUpdate(sql)
            }
        }
    }

    @Timed
    override fun load(key: AclKey): AclKeySet? {
        val loaded = loadAll(listOf(key))
        return loaded[key]
    }

    @Timed
    override fun delete(key: AclKey) {
        deleteAll(listOf(key))
    }

    override fun generateTestKey(): AclKey {
        return TestDataFactory.aclKey()
    }

    override fun generateTestValue(): AclKeySet {
        return AclKeySet(ImmutableList.of(generateTestKey(), generateTestKey(), generateTestKey()))
    }


    override fun getMapStoreConfig(): MapStoreConfig {
        return MapStoreConfig()
                .setInitialLoadMode(MapStoreConfig.InitialLoadMode.EAGER)
                .setImplementation(this)
                .setEnabled(true)
                .setWriteDelaySeconds(0)
    }

    override fun getMapName(): String {
        return HazelcastMap.PRINCIPAL_TREES.name
    }

    override fun getTable(): String {
        return PRINCIPAL_TREES.name
    }

    override fun getMapConfig(): MapConfig {
        return MapConfig(mapName)
                .setMapStoreConfig(mapStoreConfig)
                .addMapIndexConfig(MapIndexConfig(INDEX, false))
    }

    companion object {
        const val INDEX = "index[any]"
    }
}