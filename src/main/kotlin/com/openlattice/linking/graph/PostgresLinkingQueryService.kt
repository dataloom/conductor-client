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

package com.openlattice.linking.graph

import com.openlattice.data.EntityDataKey
import com.openlattice.data.storage.*
import com.openlattice.data.storage.partitions.PartitionManager
import com.openlattice.linking.EntityKeyPair
import com.openlattice.linking.LinkingQueryService
import com.openlattice.postgres.DataTables.*
import com.openlattice.postgres.PostgresArrays
import com.openlattice.postgres.PostgresColumn.*
import com.openlattice.postgres.PostgresTable.*
import com.openlattice.postgres.ResultSetAdapters
import com.openlattice.postgres.streams.PostgresIterable
import com.openlattice.postgres.streams.StatementHolder
import com.zaxxer.hikari.HikariDataSource
import java.sql.Array
import java.sql.Connection
import java.sql.ResultSet
import java.util.*
import java.util.function.Function
import java.util.function.Supplier

private const val BLOCK_SIZE_FIELD = "block_size"
private const val AVG_SCORE_FIELD = "avg_score"

/**
 * The class implements the necessary SQL queries and logic for linking operations as defined by [LinkingQueryService].
 *
 * @param hds A hikari datasource that can be used for executing SQL.
 */
class PostgresLinkingQueryService(private val hds: HikariDataSource, private val partitionManager: PartitionManager) : LinkingQueryService {

    override fun lockClustersForUpdates(clusters: Set<UUID>): Connection {
        val connection = hds.connection
        connection.autoCommit = false

        val psLocks = connection.prepareStatement(LOCK_CLUSTERS_SQL)
        clusters.toSortedSet().forEach {
            psLocks.setObject(1, it)
            psLocks.addBatch()
        }
        psLocks.executeBatch()

        return connection
    }

    override fun getLinkableEntitySets(
            linkableEntityTypeIds: Set<UUID>,
            entitySetBlacklist: Set<UUID>,
            whitelist: Set<UUID>
    ): PostgresIterable<UUID> {
        return PostgresIterable(Supplier {
            val connection = hds.connection
            val ps = connection.prepareStatement(LINKABLE_ENTITY_SET_IDS)
            val entityTypeArr = PostgresArrays.createUuidArray(connection, linkableEntityTypeIds)
            val blackListArr = PostgresArrays.createUuidArray(connection, entitySetBlacklist)
            val whitelistArr = PostgresArrays.createUuidArray(connection, whitelist)
            ps.setArray(1, entityTypeArr)
            ps.setArray(2, blackListArr)
            ps.setArray(3, whitelistArr)
            val rs = ps.executeQuery()
            StatementHolder(connection, ps, rs)
        }, Function { ResultSetAdapters.id(it) })
    }

    override fun getEntitiesNeedingLinking(entitySetId: UUID, limit: Int): PostgresIterable<EntityDataKey> {
        return PostgresIterable(Supplier {
            val connection = hds.connection
            val ps = connection.prepareStatement(ENTITY_KEY_IDS_NEEDING_LINKING)
            val partitions = getPartitionsAsPGArray(connection, entitySetId)
            ps.setArray(1, partitions)
            ps.setObject(2, entitySetId)
            ps.setInt(3, limit)
            val rs = ps.executeQuery()
            StatementHolder(connection, ps, rs)
        }, Function {
            EntityDataKey(ResultSetAdapters.entitySetId(it), ResultSetAdapters.id(it))
        })
    }

    override fun getEntitiesNotLinked(entitySetIds: Set<UUID>, limit: Int): PostgresIterable<Pair<UUID, UUID>> {
        return PostgresIterable(Supplier {
            val connection = hds.connection
            val ps = connection.prepareStatement(ENTITY_KEY_IDS_NOT_LINKED)
            val arr = PostgresArrays.createUuidArray(connection, entitySetIds)
            val partitions = getPartitionsAsPGArray(connection, entitySetIds)
            ps.setArray(1, partitions)
            ps.setArray(2, arr)
            ps.setInt(3, limit)
            val rs = ps.executeQuery()
            StatementHolder(connection, ps, rs)
        }, Function { ResultSetAdapters.entitySetId(it) to ResultSetAdapters.id(it) })
    }

    override fun updateIdsTable(clusterId: UUID, newMember: EntityDataKey): Int {
        hds.connection.use { connection ->
            connection.prepareStatement(UPDATE_LINKED_ENTITIES_SQL).use { ps ->
                val partitions = getPartitionsAsPGArray(connection, newMember.entitySetId)
                ps.setObject(1, clusterId)
                ps.setArray(2, partitions)
                ps.setObject(3, newMember.entitySetId)
                ps.setObject(4, newMember.entityKeyId)
                return ps.executeUpdate()
            }
        }
    }

    override fun getClustersForIds( dataKeys: Set<EntityDataKey> ): Map<UUID, Map<EntityDataKey, Map<EntityDataKey, Double>>> {
        return PostgresIterable<Pair<UUID, Pair<EntityDataKey, Pair<EntityDataKey, Double>>>>(
                Supplier {
                    val connection = hds.connection
                    val ps = connection.prepareStatement(buildClusterContainingSql( dataKeys ))
                    val rs = ps.executeQuery()
                    StatementHolder(connection, ps, rs)
                },
                Function {
                    val linkingId = ResultSetAdapters.linkingId(it)
                    val src = ResultSetAdapters.srcEntityDataKey(it)
                    val dst = ResultSetAdapters.dstEntityDataKey(it)
                    val score = ResultSetAdapters.score(it)
                    linkingId to (src to (dst to score))
                })
                .groupBy({ it.first }, { it.second })
                .mapValues {
                    it.value.groupBy( { src -> src.first }, { dstScore -> dstScore.second })
                            .mapValues { dstScores -> dstScores.value.toMap() }
                }
    }

    override fun createOrUpdateLink( linkingId: UUID, cluster: Map<UUID, LinkedHashSet<UUID>> ) {
        hds.connection.use { connection ->
            connection.prepareStatement( createOrUpdateLinkFromEntity() ).use { ps ->
                val version = System.currentTimeMillis()
                cluster.forEach { ( esid, ekids ) ->
                    val partitionsForEsid = getPartitionsAsPGArray(connection, esid)
                    ekids.forEach { ekid ->
                        ps.setObject(1, linkingId )
                        ps.setLong(2, version )
                        ps.setObject( 3, esid )
                        ps.setObject( 4, ekid )
                        ps.setArray( 5, partitionsForEsid )
                        ps.addBatch()
                    }
                }
                ps.executeUpdate()
            }
        }
    }

    override fun createLinks(linkingId: UUID, toAdd: Set<EntityDataKey>): Int {
        hds.connection.use { connection ->
            connection.prepareStatement( createOrUpdateLinkFromEntity() ).use { ps ->
                val version = System.currentTimeMillis()

                toAdd.forEach { edk ->
                    val partitions = getPartitionsAsPGArray(connection, edk.entitySetId)
                    ps.setObject(1, linkingId) // ID value
                    ps.setLong(2, version)
                    ps.setObject(3, edk.entitySetId) // esid
                    ps.setObject(4, edk.entityKeyId) // origin id
                    ps.setArray(5, partitions)
                    ps.addBatch()
                }
                return ps.executeUpdate()
            }
        }
    }

    override fun tombstoneLinks(linkingId: UUID, toRemove: Set<EntityDataKey>): Int {
        hds.connection.use { connection ->
            connection.prepareStatement( tombstoneLinkForEntity ).use { ps ->
                val version = System.currentTimeMillis()
                toRemove.forEach { edk ->
                    val partitions = PostgresArrays.createIntArray(connection, partitionManager.getAllPartitions())
                    ps.setLong(1, version)
                    ps.setLong(2, version)
                    ps.setLong(3, version)
                    ps.setObject(4, edk.entitySetId) // esid
                    ps.setObject(5, linkingId) // ID value
                    ps.setObject(6, edk.entityKeyId) // origin id
                    ps.setArray(7, partitions)
                    println(ps.toString())
                    ps.addBatch()
                }
                return ps.executeUpdate()
            }
        }
    }

    override fun getClusterFromLinkingId( linkingId: UUID ): Map<EntityDataKey, Map<EntityDataKey, Double>> {
        return PostgresIterable<Pair<EntityDataKey, Pair<EntityDataKey, Double>>>(
                Supplier {
                    val connection = hds.connection
                    val ps = connection.prepareStatement( CLUSTER_CONTAINING_SQL)
                    ps.setObject(1, linkingId )
                    val rs = ps.executeQuery()
                    StatementHolder(connection, ps, rs)
                },
                Function {
                    val src = ResultSetAdapters.srcEntityDataKey(it)
                    val dst = ResultSetAdapters.dstEntityDataKey(it)
                    val score = ResultSetAdapters.score(it)
                    src to (dst to score)
                })
                .groupBy({ it.first }, { it.second })
                .mapValues {
                    it.value.toMap()
                }
    }

    override fun insertMatchScores(
            connection: Connection,
            clusterId: UUID,
            scores: Map<EntityDataKey, Map<EntityDataKey, Double>>
    ): Int {
        connection.use { conn ->
            conn.prepareStatement(INSERT_SQL).use { ps ->
                scores.forEach { ( srcEntityDataKey, dst ) ->
                    dst.forEach { ( dstEntityDataKey, score ) ->
                        ps.setObject(1, clusterId)
                        ps.setObject(2, srcEntityDataKey.entitySetId)
                        ps.setObject(3, srcEntityDataKey.entityKeyId)
                        ps.setObject(4, dstEntityDataKey.entitySetId)
                        ps.setObject(5, dstEntityDataKey.entityKeyId)
                        ps.setDouble(6, score)
                        ps.addBatch()
                    }
                }
                val insertCount = ps.executeBatch().sum()
                conn.commit()
                return insertCount
            }
        }
    }

    override fun deleteNeighborhood(entity: EntityDataKey, positiveFeedbacks: Collection<EntityKeyPair>): Int {
        val deleteNeighborHoodSql = DELETE_NEIGHBORHOOD_SQL +
                if (positiveFeedbacks.isNotEmpty()) " AND NOT ( ${buildFilterEntityKeyPairs(positiveFeedbacks)} )" else ""
        hds.connection.use {
            it.prepareStatement(deleteNeighborHoodSql).use {
                it.setObject(1, entity.entitySetId)
                it.setObject(2, entity.entityKeyId)
                it.setObject(3, entity.entitySetId)
                it.setObject(4, entity.entityKeyId)
                return it.executeUpdate()
            }
        }
    }

    override fun deleteNeighborhoods(entitySetId: UUID, entityKeyIds: Set<UUID>): Int {
        hds.connection.use { connection ->
            val arr = PostgresArrays.createUuidArray(connection, entityKeyIds)
            connection.prepareStatement(DELETE_NEIGHBORHOODS_SQL).use { ps ->
                ps.setObject(1, entitySetId)
                ps.setArray(2, arr)
                ps.setObject(3, entitySetId)
                ps.setArray(4, arr)
                return ps.executeUpdate()
            }
        }
    }

    override fun deleteEntitySetNeighborhood(entitySetId: UUID): Int {
        hds.connection.use { connection ->
            connection.prepareStatement(DELETE_ENTITY_SET_NEIGHBORHOOD_SQL).use { ps ->
                ps.setObject(1, entitySetId)
                ps.setObject(2, entitySetId)
                return ps.executeUpdate()
            }
        }
    }

    override fun getEntityKeyIdsOfLinkingIds(
            linkingIds: Set<UUID>,
            normalEntitySetIds: Set<UUID>?
    ): PostgresIterable<Pair<UUID, Set<UUID>>> {
        return PostgresIterable(
                Supplier {
                    val connection = hds.connection
                    val ps = connection.prepareStatement(buildEntityKeyIdsOfLinkingIdsSql(normalEntitySetIds != null))
                    val linkingIdsArray = PostgresArrays.createUuidArray(connection, linkingIds)

                    ps.setArray(1, linkingIdsArray)
                    if (normalEntitySetIds != null) {
                        ps.setArray(2, PostgresArrays.createUuidArray(connection, normalEntitySetIds))
                    }
                    val rs = ps.executeQuery()
                    StatementHolder(connection, ps, rs)
                },
                Function<ResultSet, Pair<UUID, Set<UUID>>> {
                    val linkingId = ResultSetAdapters.linkingId(it)
                    val entityKeyIds = ResultSetAdapters.entityKeyIds(it)
                    linkingId to entityKeyIds
                })
    }

    private fun getPartitionsAsPGArray(connection: Connection, entitySetId: UUID ): Array? {
        val partitions = partitionManager.getEntitySetPartitions(entitySetId)
        return PostgresArrays.createIntArray(connection, partitions)
    }

    private fun getPartitionsAsPGArray(connection: Connection, entitySetIds: Set<UUID>): Array? {
        val partitions = partitionManager.getPartitionsByEntitySetId(entitySetIds).values.flatten()
        return PostgresArrays.createIntArray(connection, partitions)
    }
}

internal fun uuidString(id: UUID): String {
    return "'$id'::uuid"
}

/**
 * MATCHED_ENTITIES Queries
 */
private val COLUMNS = MATCHED_ENTITIES.columns.joinToString("," ) { it.name }

internal fun buildClusterContainingSql(dataKeys: Set<EntityDataKey>): String {
    val dataKeysSql = dataKeys.joinToString(",") { "('${it.entitySetId}','${it.entityKeyId}')" }
    return "SELECT * " +
            "FROM ${MATCHED_ENTITIES.name} " +
            "WHERE ((${SRC_ENTITY_SET_ID.name},${SRC_ENTITY_KEY_ID.name}) IN ($dataKeysSql)) " +
            "OR ((${DST_ENTITY_SET_ID.name},${DST_ENTITY_KEY_ID.name}) IN ($dataKeysSql))"
}

internal fun buildFilterEntityKeyPairs(entityKeyPairs: Collection<EntityKeyPair>): String {
    return entityKeyPairs.joinToString(" OR ") {
        "( (${SRC_ENTITY_SET_ID.name} = ${uuidString(
                it.first.entitySetId
        )} AND ${SRC_ENTITY_KEY_ID.name} = ${uuidString(it.first.entityKeyId)} " +
                "AND ${DST_ENTITY_SET_ID.name} = ${uuidString(
                        it.second.entitySetId
                )} AND ${DST_ENTITY_KEY_ID.name} = ${uuidString(it.second.entityKeyId)})" +
                " OR " +
                "(${SRC_ENTITY_SET_ID.name} = ${uuidString(
                        it.second.entitySetId
                )} AND ${SRC_ENTITY_KEY_ID.name} = ${uuidString(it.second.entityKeyId)} " +
                "AND ${DST_ENTITY_SET_ID.name} = ${uuidString(
                        it.first.entitySetId
                )} AND ${DST_ENTITY_KEY_ID.name} = ${uuidString(it.first.entityKeyId)}) )"
    }
}

/**
 * Returns SQL to select normal entity key ids of linking ids. Bind order is as folows:
 *
 * 1. linkingIds
 * 2. (only if filterEntitySetIds is true) normalEntitySetIds
 */
internal fun buildEntityKeyIdsOfLinkingIdsSql(filterEntitySetIds: Boolean): String {
    val maybeEntitySetIdsClause = if (filterEntitySetIds) "AND ${ENTITY_SET_ID.name} = ANY(?) " else ""

    return "SELECT ${LINKING_ID.name}, array_agg(${ID.name}) AS ${ENTITY_KEY_IDS_COL.name} " +
            "FROM ${IDS.name} " +
            "AND ${VERSION.name} > 0 " +
            "AND ${LINKING_ID.name} IS NOT NULL " +
            "AND ${LINKING_ID.name} = ANY( ? ) " +
            maybeEntitySetIdsClause +
            "GROUP BY ${LINKING_ID.name}"
}

private val LOCK_CLUSTERS_SQL = "SELECT 1 FROM ${MATCHED_ENTITIES.name} WHERE ${LINKING_ID.name} = ? FOR UPDATE"

private val CLUSTER_CONTAINING_SQL = "SELECT * FROM ${MATCHED_ENTITIES.name} WHERE ${LINKING_ID.name} = ANY(?)"

private val DELETE_NEIGHBORHOOD_SQL = "DELETE FROM ${MATCHED_ENTITIES.name} " +
        "WHERE ( (${SRC_ENTITY_SET_ID.name} = ? AND ${SRC_ENTITY_KEY_ID.name} = ?) " +
        "OR (${DST_ENTITY_SET_ID.name} = ? AND ${DST_ENTITY_KEY_ID.name} = ?) )"

private val DELETE_NEIGHBORHOODS_SQL = "DELETE FROM ${MATCHED_ENTITIES.name} WHERE " +
        "(${SRC_ENTITY_SET_ID.name} = ? AND ${SRC_ENTITY_KEY_ID.name} = ANY(?)) OR " +
        "(${DST_ENTITY_SET_ID.name} = ? AND ${DST_ENTITY_KEY_ID.name} = ANY(?)) "

private val DELETE_ENTITY_SET_NEIGHBORHOOD_SQL = "DELETE FROM ${MATCHED_ENTITIES.name} " +
        "WHERE ${SRC_ENTITY_SET_ID.name} = ? OR ${DST_ENTITY_SET_ID.name} = ? "

private val INSERT_SQL = "INSERT INTO ${MATCHED_ENTITIES.name} ($COLUMNS) VALUES (?,?,?,?,?,?) " +
        "ON CONFLICT ON CONSTRAINT matched_entities_pkey " +
        "DO UPDATE SET ${SCORE.name} = EXCLUDED.${SCORE.name}"

/**
 * IDS queries
 */
private val UPDATE_LINKED_ENTITIES_SQL = "UPDATE ${IDS.name} " +
        "SET ${LINKING_ID.name} = ?, ${LAST_LINK.name} = now() " +
        "WHERE ${PARTITION.name} = ANY(?) AND ${ENTITY_SET_ID.name} = ? AND ${ID_VALUE.name}= ?"

private val ENTITY_KEY_IDS_NEEDING_LINKING = "SELECT ${ENTITY_SET_ID.name},${ID.name} " +
        "FROM ${IDS.name} " +
        "WHERE ${PARTITION.name} = ANY(?) " +
            "AND ${ENTITY_SET_ID.name} = ? " +
            "AND ${LAST_LINK.name} < ${LAST_WRITE.name} " +
            "AND ( ${LAST_INDEX.name} >= ${LAST_WRITE.name} ) " +
            "AND ( ${LAST_INDEX.name} > '-infinity'::timestamptz ) " +
            "AND ${VERSION.name} > 0 " +
        "LIMIT ?"

private val ENTITY_KEY_IDS_NOT_LINKED = "SELECT ${ENTITY_SET_ID.name},${ID.name} " +
        "FROM ${IDS.name} " +
        "WHERE ${PARTITION.name} = ANY(?) AND ${ENTITY_SET_ID.name} = ANY(?) AND ${LAST_LINK.name} < ${LAST_WRITE.name} " +
        "AND ${VERSION.name} > 0 LIMIT ?"

private val LINKABLE_ENTITY_SET_IDS = "SELECT ${ID.name} " +
        "FROM ${ENTITY_SETS.name} " +
        "WHERE ${ENTITY_TYPE_ID.name} = ANY(?) AND NOT ${ID.name} = ANY(?) AND ${ID.name} = ANY(?) "
