package com.mckoss.message_killer

import org.junit.Assert.assertEquals
import org.junit.Test

class SpamExporterTest {
    @Test
    fun csvQuotesCommasQuotesAndNewlines() {
        val entry = SpamStore.Entry(
            id = 1, source = "sms", status = "deleted", sender = "(302) 464-8095",
            body = "Chip in \$5, \"now\"\nbefore midnight", messageTime = 0, filedAt = 0,
            score = 6.0, reasons = listOf("Asks for a donation", "Fundraising deadline"), smsId = 9,
        )
        val lines = SpamExporter.toCsv(listOf(entry)).split("\r\n")
        assertEquals("received_at,sender,body,status,score,reasons", lines[0])
        assertEquals(
            "\"1970-01-01T00:00:00Z\",\"(302) 464-8095\",\"Chip in \$5, \"\"now\"\"\nbefore midnight\"," +
                "\"deleted\",\"6.0\",\"Asks for a donation; Fundraising deadline\"",
            lines[1],
        )
    }
}
