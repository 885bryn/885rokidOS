package com.rokidos885.phone.traffic

import com.rokidos885.phone.nav.RouteOption
import com.rokidos885.phone.nav.SpeedInterval
import com.rokidos885.protocol.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Routes REST requests. Credentials and response bodies never enter logs or error messages. */
class RoutesApiClient(private val apiKey:String,private val http:OkHttpClient=OkHttpClient.Builder()
    .callTimeout(25,TimeUnit.SECONDS).build(),private val androidPackage:String?=null,private val androidCert:String?=null) {
    suspend fun computeRoutes(origin:LatLng,originHeadingDeg:Int?,destination:LatLng,
        destinationPlaceId:String?,alternatives:Boolean=true):Result<List<RouteOption>> = withContext(Dispatchers.IO) {
        if(apiKey.isBlank())return@withContext Result.failure(IllegalStateException("Configure the Routes API key to calculate routes."))
        try {
            val request=Request.Builder().url("https://routes.googleapis.com/directions/v2:computeRoutes")
                .header("X-Goog-Api-Key",apiKey).header("X-Goog-FieldMask",FIELD_MASK)
                .apply {if(androidPackage!=null)header("X-Android-Package",androidPackage);if(androidCert!=null)header("X-Android-Cert",androidCert)}
                .post(requestBody(origin,originHeadingDeg,destination,destinationPlaceId,alternatives)
                    .toRequestBody("application/json".toMediaType())).build()
            val body=suspendCancellableCoroutine<String> { continuation ->
                val call=http.newCall(request)
                continuation.invokeOnCancellation {call.cancel()}
                call.enqueue(object:Callback {
                    override fun onFailure(call:Call,e:java.io.IOException) {
                        if(continuation.isActive)continuation.resumeWithException(e)
                    }
                    override fun onResponse(call:Call,response:Response) {
                        val result=runCatching {response.use {
                            check(it.isSuccessful){"Route request failed (HTTP ${it.code}). Check API access, billing and key restrictions."}
                            val body=requireNotNull(it.body)
                            check(body.contentLength()<=4_000_000){"Route response is too large."}
                            val bytes=body.byteStream().use {input ->
                                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                                while(out.size()<=4_000_000){val count=input.read(buffer);if(count<0)break;out.write(buffer,0,count)}
                                out.toByteArray()
                            }
                            check(bytes.size<=4_000_000){"Route response is too large."}
                            bytes.toString(Charsets.UTF_8)
                        }}
                        if(continuation.isActive)result.fold(continuation::resume,continuation::resumeWithException)
                    }
                })
            }
            Result.success(parse(body))
        } catch(e:CancellationException){throw e}
        catch(e:Exception){Result.failure(IllegalStateException(if(e is IllegalStateException)e.message else "Could not retrieve routes. Check your connection."))}
    }
    companion object {
        fun signingCertificate(context:android.content.Context):String?=runCatching {
            val info=context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            val signer=info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
            java.security.MessageDigest.getInstance("SHA-1").digest(signer.toByteArray()).joinToString(""){"%02X".format(it)}
        }.getOrNull()

        const val FIELD_MASK="routes.routeToken,routes.duration,routes.staticDuration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.description,routes.warnings,routes.travelAdvisory.speedReadingIntervals"
        fun requestBody(origin:LatLng,heading:Int?,destination:LatLng,placeId:String?,alternatives:Boolean)=buildJsonObject {
            fun JsonObjectBuilder.location(point:LatLng,bearing:Int?=null) {
                putJsonObject("location") {
                    putJsonObject("latLng"){put("latitude",point.lat);put("longitude",point.lng)}
                    if(bearing!=null)put("heading",((bearing%360)+360)%360)
                }
            }
            putJsonObject("origin"){location(origin,heading)}
            putJsonObject("destination"){if(!placeId.isNullOrBlank())put("placeId",placeId) else location(destination)}
            put("travelMode","DRIVE");put("routingPreference","TRAFFIC_AWARE_OPTIMAL")
            put("computeAlternativeRoutes",alternatives);put("polylineQuality","HIGH_QUALITY")
            putJsonArray("extraComputations"){add("TRAFFIC_ON_POLYLINE")}
            put("languageCode","en-US");put("units","METRIC")
        }.toString()
        fun parse(body:String):List<RouteOption> {
            val root=Json.parseToJsonElement(body).jsonObject
            return root["routes"]?.jsonArray.orEmpty().mapIndexed { index,element ->
                val r=element.jsonObject
                fun string(key:String)=r[key]?.jsonPrimitive?.contentOrNull.orEmpty()
                fun seconds(key:String)=string(key).removeSuffix("s").toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0
                val polyline=r["polyline"]?.jsonObject?.get("encodedPolyline")?.jsonPrimitive?.content.orEmpty()
                require(polyline.isNotBlank()){"Route geometry is unavailable."}
                RouteOption(string("routeToken"),polyline,r["distanceMeters"]?.jsonPrimitive?.intOrNull ?: 0,
                    seconds("duration"),seconds("staticDuration"),string("description").ifBlank {"Route ${index+1}"},
                    r["travelAdvisory"]?.jsonObject?.get("speedReadingIntervals")?.jsonArray.orEmpty().map { interval ->
                        val i=interval.jsonObject
                        SpeedInterval(i["startPolylinePointIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                            i["endPolylinePointIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                            i["speed"]?.jsonPrimitive?.content.orEmpty())
                    },r["warnings"]?.jsonArray.orEmpty().map {it.jsonPrimitive.content})
            }
        }
    }
}
