package com.rokidos885.phone.alerts

import com.rokidos885.protocol.AlertKind
import com.rokidos885.protocol.LatLng
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SabreProtocolTest {
    @Test fun createsDocumentedReadOnlyRequests() {
        val hello = Json.parseToJsonElement(SabreProtocol.handshakeRequest("reply")).jsonObject
        assertEquals(1, hello.getValue("protocol_version").jsonPrimitive.int)
        val fetch = Json.parseToJsonElement(SabreProtocol.fetchRequest("r1", LatLng(1.0, 2.0), "reply")).jsonObject
        assertEquals("r1", fetch.getValue("request_id").jsonPrimitive.content)
        assertEquals(5000.0, fetch.getValue("radius_m").jsonPrimitive.double, 0.0)
    }
    @Test fun parsesHandshakeAndBatchesWithOptionalAndFutureFields() {
        val hello = SabreProtocol.handshakeResponse("""{"request_action":"app.sabre.wzsabre.FETCH","future":42}""")
        assertEquals("app.sabre.wzsabre.FETCH", hello.requestAction)
        val batch = SabreProtocol.batch("""{"request_id":"r1","error_message":null,"response":{
          "n_batches":2,"batch_id":1,"alerts":[
          {"alert_source":"waze","alert_id":"a1","type":"POLICE_HIDING","lat":1,"lon":2,"report_ts":100,"heading_deg":-720,"future":true},
          {"alert_source":"waze","alert_id":"a2","type":"MOBILE_CAMERA","lat":1,"lon":2,"report_ts":101,"confirm_ts":110},
          {"alert_source":"waze","alert_id":"a3","type":"OTHER","lat":1,"lon":2,"report_ts":100},
          {"alert_source":"waze","alert_id":"w1","type":"POLICE","lat":1,"lon":2,"report_ts":100},
          {"alert_source":"waze","alert_id":"w2","type":"CAMERA","lat":1,"lon":2,"report_ts":100},
          {"alert_source":"waze","alert_id":"w3","type":"POLICE_WITH_MOBILE_CAMERA","lat":1,"lon":2,"report_ts":100},
          {"alert_source":"waze","alert_id":"w4","type":"HAZARD_ON_ROAD","lat":1,"lon":2,"report_ts":100},
          {"alert_source":"waze","alert_id":"bad","type":"POLICE_VISIBLE","lat":200,"lon":2,"report_ts":100}
        ]}}""")
        // WzSabre sends Waze names (POLICE, CAMERA, POLICE_WITH_MOBILE_CAMERA) as well as the spec names.
        assertEquals(5, batch.incidents.size)
        assertEquals(listOf(AlertKind.POLICE, AlertKind.SPEED_CAMERA, AlertKind.POLICE, AlertKind.SPEED_CAMERA, AlertKind.SPEED_CAMERA),
            batch.incidents.map { it.kind })
        assertEquals(AlertKind.POLICE, batch.incidents.first().kind)
        assertEquals(100_000L, batch.incidents.first().reportedAtMs)
        assertTrue(batch.incidents.first().headings.isEmpty())
        assertEquals(1_310_000L, batch.incidents[1].expiresAtMs)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidBatchIndex() {
        SabreProtocol.batch("""{"request_id":"r1","response":{"n_batches":1,"batch_id":1,"alerts":[]}}""")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPluginError() {
        SabreProtocol.batch("""{"request_id":"r1","error_message":"offline","response":null}""")
    }
    @Test fun assemblesOutOfOrderBatchesAndIgnoresOtherRequestsAndDuplicates() {
        val batches = SabreBatches("r1")
        val incident = Incident("p1", AlertKind.POLICE, LatLng(0.0, 0.0), "Police reported", 0)
        val last = SabreProtocol.Batch("r1", 2, 1, listOf(incident))
        assertNull(batches.accept(last.copy(requestId = "old")))
        assertNull(batches.accept(last))
        assertNull(batches.accept(last))
        assertEquals(listOf(incident), batches.accept(last.copy(index = 0)))
    }
}
