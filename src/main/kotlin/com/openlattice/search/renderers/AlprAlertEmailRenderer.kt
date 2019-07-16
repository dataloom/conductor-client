package com.openlattice.search.renderers

import com.google.common.base.Optional
import com.google.common.collect.Lists
import com.google.common.collect.Maps
import com.google.common.collect.SetMultimap
import com.openlattice.mail.RenderableEmailRequest
import com.openlattice.search.requests.PersistentSearch
import jodd.mail.EmailAttachment
import org.apache.olingo.commons.api.edm.FullQualifiedName
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.imageio.ImageIO

private val logger = LoggerFactory.getLogger(AlprAlertEmailRenderer::class.java)

private const val FROM_EMAIL = "courier@openlattice.com"
private const val TEMPLATE_PATH = "mail/templates/shared/AlprAlertTemplate.mustache"
private const val MIME_TYPE_PREFIX = "image/"
private const val JPEG = "jpeg"
private const val PNG = "png"

private val LAST_READ_FQN = FullQualifiedName("ol.datetimelastreported")
private val EXPIRATION_FQN = FullQualifiedName("ol.datetimeend")
private val SEARCH_QUERY_FQN = FullQualifiedName("ol.searchquery")
private val READ_TIMESTAMP_FQN = FullQualifiedName("ol.datelogged")
private val PERSON_ID_FQN = FullQualifiedName("nc.SubjectIdentification")

// alert details
private val CASE_NUM_FQN = FullQualifiedName("criminaljustice.casenumber")
private val SEARCH_REASON_FQN = FullQualifiedName("ol.searchreason")

// vehicle details
private val LICENSE_PLATE_IMAGE_FQN = FullQualifiedName("ol.licenseplateimage")
private val VEHICLE_IMAGE_FQN = FullQualifiedName("ol.vehicleimage")
private val LOCATION_COORDS_FQN = FullQualifiedName("ol.locationcoordinates")
private val LICENSE_NUMBER_FQN = FullQualifiedName("vehicle.licensenumber")
private val LICENSE_STATE_FQN = FullQualifiedName("vehicle.licensestate")
private val MAKE_FQN = FullQualifiedName("vehicle.make")
private val MODEL_FQN = FullQualifiedName("vehicle.model")
private val YEAR_FQN = FullQualifiedName("vehicle.year")
private val COLOR_FQN = FullQualifiedName("vehicle.color")
private val AGENCY_NAME_FQN = FullQualifiedName("publicsafety.agencyname")
private val CAMERA_ID_FQN = FullQualifiedName("ol.resourceid")
private val HIT_TYPE_FQN = FullQualifiedName("ol.description")

class AlprAlertEmailRenderer {

    companion object {

        private val dateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm a, z").withZone(
                TimeZone.getTimeZone("America/Los_Angeles").toZoneId()
        )

        private fun getFirstValue(entity: Map<FullQualifiedName, Set<Any>>, fqn: FullQualifiedName): String? {
            val values = entity[fqn] ?: emptySet()
            return if (values.isEmpty()) {
                null
            } else values.iterator().next().toString()

        }

        private fun getImage(imageUrlPath: String?, fileType: String): ByteArrayOutputStream? {
            if (imageUrlPath == null) {
                return null
            }

            var stream: ByteArrayOutputStream? = null

            try {
                val imageUrl = URL(imageUrlPath)
                val bufferedImage = ImageIO.read(imageUrl)
                val baos = ByteArrayOutputStream()
                ImageIO.write(bufferedImage, fileType, baos)
                baos.flush()
                stream = baos
            } catch (e: IOException) {
                logger.error("Unable to load image for url {}", imageUrlPath, e)
            }

            return stream

        }

        private fun getMapImage(coords: String?, mapboxToken: String): ByteArrayOutputStream? {
            if (coords == null) {
                return null
            }

            val latAndLon = coords.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (latAndLon.size < 2) {
                return null
            }

            val lat = latAndLon[0]
            val lon = latAndLon[1]

            val url = StringBuilder("https://api.mapbox.com/v4/mapbox.streets/pin-l-car+000(")
                    .append(lon)
                    .append(",")
                    .append(lat)
                    .append(")/")
                    .append(lon)
                    .append(",")
                    .append(lat)
                    .append(",15/600x600.png?access_token=")
                    .append(mapboxToken)
                    .toString()

            return getImage(url, PNG)
        }

        private fun extractVehicleInfo(vehicleRead: Map<FullQualifiedName, Set<Any>>): Map<String, Any> {

            val values = Maps.newHashMap<String, Any>()

            val readDateTimeList = vehicleRead[READ_TIMESTAMP_FQN] ?: emptySet()

            values["readDateTime"] = if (readDateTimeList.isEmpty()) "" else OffsetDateTime.parse(
                    readDateTimeList.first().toString()
            ).format(dateTimeFormatter)
            values["hitType"] = getFirstValue(vehicleRead, HIT_TYPE_FQN)
            values["cameraId"] = getFirstValue(vehicleRead, CAMERA_ID_FQN)
            values["agencyId"] = getFirstValue(vehicleRead, AGENCY_NAME_FQN)
            values["make"] = getFirstValue(vehicleRead, MAKE_FQN)
            values["model"] = getFirstValue(vehicleRead, MODEL_FQN)
            values["year"] = getFirstValue(vehicleRead, YEAR_FQN)
            values["color"] = getFirstValue(vehicleRead, COLOR_FQN)
            values["coordinates"] = getFirstValue(vehicleRead, LOCATION_COORDS_FQN)


            return values.filterValues { it != null }
        }

        private fun extractVehicleImages(vehicleRead: Map<FullQualifiedName, Set<Any>>, mapboxToken: String): List<EmailAttachment<*>> {

            val licenseImageUrl = getFirstValue(vehicleRead, LICENSE_PLATE_IMAGE_FQN)
            val vehicleImageUrl = getFirstValue(vehicleRead, VEHICLE_IMAGE_FQN)
            val coordinates = getFirstValue(vehicleRead, LOCATION_COORDS_FQN)

            val licenseImage = getImage(licenseImageUrl, JPEG)
            val vehicleImage = getImage(vehicleImageUrl, JPEG)
            val mapImage = getMapImage(coordinates, mapboxToken)

            val attachments = Lists.newArrayList<EmailAttachment<*>>()
            if (licenseImage != null) {
                EmailAttachment.with().content(licenseImage.toByteArray(), "$MIME_TYPE_PREFIX$JPEG").name(
                        "license-plate-image"
                ).contentId("plate")
                attachments
                        .add(
                                EmailAttachment.with()
                                        .content(licenseImage.toByteArray(), "$MIME_TYPE_PREFIX$JPEG")
                                        .name("license-plate-image")
                                        .contentId("plate")
                                        .buildByteArrayDataSource()
                        )
            }
            if (vehicleImage != null) {
                attachments.add(
                        EmailAttachment.with()
                                .content(vehicleImage.toByteArray(), "$MIME_TYPE_PREFIX$JPEG")
                                .name("vehicle-image")
                                .contentId("vehicle").buildByteArrayDataSource()
                )
            }
            if (mapImage != null) {
                attachments.add(
                        EmailAttachment.with().content(mapImage.toByteArray(), "$MIME_TYPE_PREFIX$PNG")
                                .name("map-image")
                                .contentId("map").buildByteArrayDataSource()
                )
            }

            return attachments
        }

        fun renderEmail(
                persistentSearch: PersistentSearch,
                vehicle: Map<FullQualifiedName, Set<Any>>,
                userEmail: String,
                mapboxToken: String
        ): RenderableEmailRequest {

            val caseNum = persistentSearch.alertMetadata["caseNum"]
            val licensePlate = persistentSearch.alertMetadata["licensePlate"]

            val subject = "New ALPR read for case $caseNum -- $licensePlate"

            val templateObjects: MutableMap<String, Any> = mutableMapOf()
            templateObjects.putAll(persistentSearch.alertMetadata)
            templateObjects.putAll(extractVehicleInfo(vehicle))
            templateObjects["expiration"] = persistentSearch.expiration.format(dateTimeFormatter)

            val attachments = extractVehicleImages(vehicle, mapboxToken).toTypedArray()

            return RenderableEmailRequest(
                    Optional.of(FROM_EMAIL),
                    arrayOf(userEmail),
                    Optional.absent(),
                    Optional.absent(),
                    TEMPLATE_PATH,
                    Optional.of(subject),
                    Optional.of(templateObjects),
                    Optional.of(attachments),
                    Optional.absent()
            )
        }
    }
}