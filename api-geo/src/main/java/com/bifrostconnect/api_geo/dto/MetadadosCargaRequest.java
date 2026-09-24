 package com.bifrostconnect.api_geo.dto;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;


public class MetadadosCargaRequest {


@NotNull(message = "O órgão é obrigatório")

private Long orgaoId;


@NotNull(message = "O conjunto é obrigatório")

private Long conjuntoId; // <--- Adicionado para preencher a coluna not-null do banco


@NotBlank(message = "O ano da safra é obrigatório")

private String anoSafra;


@NotBlank(message = "O EPSG de origem é obrigatório")

private String epsgOrigem;


public MetadadosCargaRequest() {

}


public MetadadosCargaRequest(Long orgaoId, Long conjuntoId, String anoSafra, String epsgOrigem) {

this.orgaoId = orgaoId;

this.conjuntoId = conjuntoId;

this.anoSafra = anoSafra;

this.epsgOrigem = epsgOrigem;

}


public Long getOrgaoId() {

return orgaoId;

}


public void setOrgaoId(Long orgaoId) {

this.orgaoId = orgaoId;

}


public Long getConjuntoId() {

return conjuntoId;

}


public void setConjuntoId(Long conjuntoId) {

this.conjuntoId = conjuntoId;

}


public String getAnoSafra() {

return anoSafra;

}


public void setAnoSafra(String anoSafra) {

this.anoSafra = anoSafra;

}


public String getEpsgOrigem() {

return epsgOrigem;

}


public void setEpsgOrigem(String epsgOrigem) {

this.epsgOrigem = epsgOrigem;

}

} 