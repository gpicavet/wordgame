package app.wordgame.server

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NicknamesTest {
    @Test
    fun refusesInsultsHoweverWritten() {
        for (nickname in listOf("connard", "CONNNARD", "c0nnard", "connard93", "Gros_Con", "GrosCon", "con93", "SaloPe", "xxFuckxx", "pédé", "WordWok_officiel", "BelzeBot2", "modo")) {
            assertNotNull(Nicknames.refusal(nickname), nickname)
        }
    }

    @Test
    fun acceptsInnocentNicknames() {
        for (nickname in listOf("greg", "prout", "Constance", "Conan", "salopette", "Scunthorpe", "badminton", "Pascal", "deputee", "computer", "Nicolas_93")) {
            assertNull(Nicknames.refusal(nickname), nickname)
        }
    }
}
