package com.bifrostconnect.api_geo.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "arquivo_original")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArquivoOriginal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "processo_id", nullable = false)
    private Processo processo;

    @Column(name = "nome_arquivo", nullable = false)
    private String nomeArquivo;

    @Column(name = "hash_sha256", nullable = false)
    private String hashSha256;

    @Column(name = "tamanho_bytes", nullable = false)
    private Long tamanhoBytes;

    @Column(name = "caminho_storage", nullable = false)
    private String caminhoStorage;

    @Column(name = "data_upload", insertable = false, updatable = false)
    private LocalDateTime dataUpload;
}