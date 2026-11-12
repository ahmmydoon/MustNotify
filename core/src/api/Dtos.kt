package com.salahoard.mustnotify.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Only the fields we actually use are modelled. Unknown keys are ignored on purpose
 * (see MustCloudApi.json), so the vendor's `secret`, account details, addresses and
 * coordinates are never even parsed, let alone stored.
 */

/** `data` of POST /ppr/app/login/pub/login. The JWT in the `Auth` response header is not needed. */
@Serializable
data class LoginData(
    val token: String,
)

/** `data` of GET /dev/api/auth/app/dev/V2/listTDeviceInfo. */
@Serializable
data class DeviceListData(
    val items: List<DeviceItem> = emptyList(),
)

@Serializable
data class DeviceItem(
    val deviceName: String? = null,
    val productName: String? = null,
    val pn: String,
    val sn: String,
    val devcode: Int,
    val devaddr: Int,
    val deviceTypeImg: String? = null,
    /** Device timezone as an offset from UTC in seconds (e.g. 10800 = UTC+3). */
    val timezone: Int = 0,
)

/** One entry of the `data` array of GET /ppe/api/auth/app/queryDeviceOneDataxxx. */
@Serializable
data class ReadingItem(
    val id: String,
    val title: String? = null,
    val unit: String? = null,
    @SerialName("val") val value: String? = null,
    val date: String? = null,
)

/**
 * What the app stores locally for a chosen device. pn and sn are the only thing protecting the
 * unauthenticated reading endpoint, so treat them like a password: never log, never commit.
 */
@Serializable
data class DeviceRef(
    val name: String,
    val productName: String?,
    val pn: String,
    val sn: String,
    val devcode: Int,
    val devaddr: Int,
    val imageUrl: String?,
    val utcOffsetSeconds: Int,
)

fun DeviceItem.toRef(): DeviceRef = DeviceRef(
    name = deviceName ?: productName ?: pn,
    productName = productName,
    pn = pn,
    sn = sn,
    devcode = devcode,
    devaddr = devaddr,
    // The API hands out http:// links; the host also serves https, so always use that.
    imageUrl = deviceTypeImg?.let { if (it.startsWith("http://")) "https://" + it.removePrefix("http://") else it },
    utcOffsetSeconds = timezone,
)
