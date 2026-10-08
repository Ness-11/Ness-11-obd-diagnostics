package pl.obd.core

/** Adapter configuration is separate from ECU services. Every step still uses the read-only policy. */
interface AdapterProfile {
    val name: String
    val setup: List<InitializationStep>
}
data class InitializationStep(val command: ReadOnlyCommand, val optional: Boolean = false)
object Elm327Profile : AdapterProfile {
    override val name = "ELM327 Classic SPP"
    // Headers and CAF1 are a contract with the MVP parser. CFC1 delegates receive flow control to ELM.
    override val setup = listOf("ATE0", "ATL0", "ATS1", "ATH1", "ATSP0", "ATCAF1", "ATCFC1", "ATAL", "ATAT2")
        .map { InitializationStep(ReadOnlyCommand.parse(it), optional = it == "ATAL" || it == "ATAT2") }
}
