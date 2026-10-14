package com.reembolso;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-017 — README.md (como rodar e como testar) + CLAUDE.md (convencoes).
 * Criterios de aceite:
 *  - um leigo consegue `mvn test` e rodar a CLI seguindo so o README;
 *  - CLAUDE.md lista os pactos: arquivos de spec, formato de commit e a
 *    proibicao de regra de negocio fora da spec.
 */
class DocsTest {

    private static String ler(String caminho) throws IOException {
        Path p = Path.of(caminho);
        assertTrue(Files.exists(p), caminho + " precisa existir na raiz do projeto");
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("readme_leigoConsegueTestarERodar")
    void readme_leigoConsegueTestarERodar() throws Exception {
        String readme = ler("README.md");

        assertTrue(readme.contains("mvn test"), "README precisa dizer como testar: `mvn test`");
        assertTrue(readme.contains("calcular --input"), "README precisa mostrar o comando da CLI");
        assertTrue(readme.contains("--output"), "README precisa mostrar o comando completo da CLI");
        assertTrue(readme.contains("reembolso.cmd") && readme.contains("reembolso.sh"),
                "README precisa apontar os wrappers para Windows e Unix");
        assertTrue(readme.contains("JDK 17") && readme.contains("Maven"),
                "README precisa listar os pre-requisitos para um leigo");
        assertTrue(readme.contains("exemplos/despesas-exemplo.json"),
                "README precisa apontar uma entrada de exemplo pronta para rodar");
    }

    @Test
    @DisplayName("claudeMd_listaOsPactos")
    void claudeMd_listaOsPactos() throws Exception {
        String claude = ler("CLAUDE.md");

        // pacto 1: arquivos de spec sao a fonte da verdade
        assertTrue(claude.contains("spec.md") && claude.contains("plan.md")
                        && claude.contains("tasks.md") && claude.contains("DECISIONS.md"),
                "CLAUDE.md precisa listar os arquivos de spec");

        // pacto 2: formato de commit referenciando task
        assertTrue(claude.contains("feat(T-"), "CLAUDE.md precisa fixar o formato de commit feat(T-NNN)");

        // pacto 3: proibicao de regra de negocio fora da spec
        assertTrue(claude.contains("regra de negócio vive na spec")
                        || claude.contains("regra de negocio vive na spec"),
                "CLAUDE.md precisa proibir regra de negocio fora da spec");
    }
}
