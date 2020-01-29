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
package com.openlattice.ids

import com.hazelcast.core.HazelcastInstance
import com.openlattice.hazelcast.HazelcastMap
import java.nio.ByteBuffer
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class IdCipherManager(hazelCastInstance: HazelcastInstance) {
    companion object {
        private const val keyAlgorithm = "PBKDF2WithHmacSHA1"
        const val keySpecAlgorithm = "AES"
        private const val cipherAlgorithm = "AES/ECB/NoPadding"
    }

    private val factory = SecretKeyFactory.getInstance(keyAlgorithm)
    private val secretKeys = HazelcastMap.LINKED_ENTITY_SET_SECRET_KEYS.getMap(hazelCastInstance)

    fun assignSecretKey(linkingEntitySetId: UUID) {
        require(!secretKeys.containsKey(linkingEntitySetId)) {
            "Linking entity set with id $linkingEntitySetId has already a secret key."
        }

        secretKeys[linkingEntitySetId] = generateSecretKey()
    }

    private fun generateSecretKey(): SecretKeySpec {
        val spec = PBEKeySpec(
                Random.nextBytes(256).toString().toCharArray(),
                Random.nextBytes(256),
                65537,
                256
        )
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, keySpecAlgorithm)
    }

    fun encryptId(linkingEntitySetId: UUID, id: UUID): UUID {
        return encryptIds(linkingEntitySetId, setOf(id)).first()
    }

    fun encryptIds(linkingEntitySetId: UUID, ids: Set<UUID>): Set<UUID> {
        return doCipher(Cipher.ENCRYPT_MODE, linkingEntitySetId, ids)
    }

    fun encryptIdsAsMap(linkingEntitySetId: UUID, ids: Set<UUID>): Map<UUID, UUID> {
        return doCipherAsMap(Cipher.ENCRYPT_MODE, linkingEntitySetId, ids)
    }

    fun decryptId(linkingEntitySetId: UUID, id: UUID): UUID {
        return decryptIds(linkingEntitySetId, setOf(id)).first()
    }

    fun decryptIds(linkingEntitySetId: UUID, ids: Set<UUID>): Set<UUID> {
        return doCipher(Cipher.DECRYPT_MODE, linkingEntitySetId, ids)
    }

    private fun doCipher(mode: Int, linkingEntitySetId: UUID, ids: Set<UUID>): Set<UUID> {
        val cipher = Cipher.getInstance(cipherAlgorithm)
        val key = secretKeys.getValue(linkingEntitySetId)
        cipher.init(mode, key)

        return ids.map { asUuid(cipher.doFinal(asBytes(it))) }.toSet()
    }

    private fun doCipherAsMap(mode: Int, linkingEntitySetId: UUID, ids: Set<UUID>): Map<UUID, UUID> {
        val cipher = Cipher.getInstance(cipherAlgorithm)
        val key = secretKeys.getValue(linkingEntitySetId)
        cipher.init(mode, key)

        return ids.map { it to asUuid(cipher.doFinal(asBytes(it))) }.toMap()
    }

    private fun asUuid(bytes: ByteArray): UUID {
        val bb = ByteBuffer.wrap(bytes)
        val firstLong = bb.long
        val secondLong = bb.long
        return UUID(firstLong, secondLong)
    }

    private fun asBytes(uuid: UUID): ByteArray {
        val bb = ByteBuffer.wrap(ByteArray(16))
        bb.putLong(uuid.mostSignificantBits)
        bb.putLong(uuid.leastSignificantBits)
        return bb.array()
    }
}

