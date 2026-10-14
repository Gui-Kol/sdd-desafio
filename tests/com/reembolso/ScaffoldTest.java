package com.reembolso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-001 — Scaffold Maven + JDK2 17 + JUnit 5.
 * Prova que o build funciona, que a estrutura de pacotes existe e que a suite roda verde.
 */
class ScaffoldTest {

    @Test
    @DisplayName("t001_estruturaDePacotes_existeNoRepositorio")
    void t001_estruturaDePacotes_existeNoRepositorio() {
        String[] pacotes = {"cli", "io", "dominio", "motor", "materializacao"};
        for (String pacote : pacotes) {
            Path dir = Path.of("src/main/java/com/reembolso/" + pacote);
            assertTrue(Files.isDirectory(dir), "Pacote ausente: " + dir);
        }
    }

    @Test
    @DisplayName("t001_runtimeEhJava17")
    void t001_runtimeEhJava17() {
        assertEquals(17, Runtime.version().feature(), "O projeto exige Java 17 (plan.md)");
    }
}
