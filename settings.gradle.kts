plugins {
    // Provisiona automaticamente o JDK 21 quando a maquina nao o tiver instalado,
    // garantindo que o build seja reproduzivel independente do JDK do desenvolvedor.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "flash-booking"
