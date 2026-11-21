package com.bifrostconnect.api_geo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

class ConectorCriptoServiceTest {

    private ListAppender<ILoggingEvent> logs;
    private Logger root;
    private Level nivelOriginal;

    @BeforeEach
    void setUp() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        nivelOriginal = root.getLevel();
        root.setLevel(Level.TRACE);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        root.setLevel(nivelOriginal);
    }

    @Test
    @DisplayName("Cifra com prefixo v1:, IV aleatório e decifra de volta para o segredo original")
    void deveCifrarEDecifrarComIvAleatorio() {
        ConectorCriptoService service = new ConectorCriptoService("segredo-forte-teste", "");
        String segredo = "token-super-secreto-12345";

        String cifrado1 = service.cifrar(segredo);
        String cifrado2 = service.cifrar(segredo);

        assertThat(cifrado1).startsWith("v1:");
        assertThat(cifrado2).startsWith("v1:");
        assertThat(cifrado1).isNotEqualTo(cifrado2);
        assertThat(cifrado1).doesNotContain(segredo);

        assertThat(service.decifrar(cifrado1)).isEqualTo(segredo);
        assertThat(service.decifrar(cifrado2)).isEqualTo(segredo);
        assertNadaFoiLogadoCom(segredo);
    }

    @Test
    @DisplayName("Null ou branco em cifrar/decifrar retorna null")
    void deveRetornarNullParaEntradasNulasOuEmBranco() {
        ConectorCriptoService service = new ConectorCriptoService("segredo", "");

        assertThat(service.cifrar(null)).isNull();
        assertThat(service.cifrar("")).isNull();
        assertThat(service.cifrar("   ")).isNull();

        assertThat(service.decifrar(null)).isNull();
        assertThat(service.decifrar("")).isNull();
        assertThat(service.decifrar("   ")).isNull();
    }

    @Test
    @DisplayName("Hierarquia de chaves: conector.cripto.segredo -> seguranca.jwt.segredo -> chave dev fixa")
    void deveRespeitarHierarquiaDeFallbackDaChave() {
        ConectorCriptoService comJwt = new ConectorCriptoService("", "segredo-jwt-compartilhado");
        ConectorCriptoService comConectorBlankJwt = new ConectorCriptoService("   ", "segredo-jwt-compartilhado");
        ConectorCriptoService comDev = new ConectorCriptoService("", "");
        ConectorCriptoService comDev2 = new ConectorCriptoService(null, null);
        ConectorCriptoService comChavePropria = new ConectorCriptoService("segredo-exclusivo", "segredo-jwt-compartilhado");

        String segredo = "api-key-abc-999";

        String cifradoJwt = comJwt.cifrar(segredo);
        assertThat(comConectorBlankJwt.decifrar(cifradoJwt)).isEqualTo(segredo);
        assertThatThrownBy(() -> comChavePropria.decifrar(cifradoJwt))
                .isInstanceOf(RuntimeException.class);

        String cifradoDev = comDev.cifrar(segredo);
        assertThat(comDev2.decifrar(cifradoDev)).isEqualTo(segredo);
        assertThatThrownBy(() -> comJwt.decifrar(cifradoDev))
                .isInstanceOf(RuntimeException.class);
        assertNadaFoiLogadoCom(segredo);
    }

    @Test
    @DisplayName("Valor cifrado inválido ou adulterado falha explicitamente sem vazar segredo")
    void deveFalharAoDecifrarValorInvalidoOuAdulterado() {
        ConectorCriptoService service = new ConectorCriptoService("segredo-teste", "");
        String segredo = "segredo-confidencial";
        String cifrado = service.cifrar(segredo);

        assertThatThrownBy(() -> service.decifrar("sem-prefixo"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.decifrar("v1:base64_invalido!!!"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.decifrar("v1:AAAA"))
                .isInstanceOf(RuntimeException.class);

        String alterado = cifrado.substring(0, cifrado.length() - 2) + (cifrado.endsWith("AA") ? "BB" : "AA");
        assertThatThrownBy(() -> service.decifrar(alterado))
                .isInstanceOf(RuntimeException.class);

        assertNadaFoiLogadoCom(segredo);
    }

    @Test
    @DisplayName("mascararPrefixo mascara corretamente de acordo com o tamanho do segredo")
    void deveMascararPrefixoCorretamente() {
        ConectorCriptoService service = new ConectorCriptoService();

        assertThat(service.mascararPrefixo(null)).isNull();
        assertThat(service.mascararPrefixo("")).isNull();
        assertThat(service.mascararPrefixo("   ")).isNull();
        assertThat(service.mascararPrefixo("a")).isEqualTo("****");
        assertThat(service.mascararPrefixo("abcd")).isEqualTo("****");
        assertThat(service.mascararPrefixo("abcde")).isEqualTo("abcd****");
        assertThat(service.mascararPrefixo("firms_key_123456")).isEqualTo("firm****");
    }

    private void assertNadaFoiLogadoCom(String segredo) {
        for (ILoggingEvent evento : logs.list) {
            assertThat(evento.getFormattedMessage()).doesNotContain(segredo);
            IThrowableProxy erro = evento.getThrowableProxy();
            while (erro != null) {
                assertThat(String.valueOf(erro.getMessage())).doesNotContain(segredo);
                erro = erro.getCause();
            }
        }
    }
}
