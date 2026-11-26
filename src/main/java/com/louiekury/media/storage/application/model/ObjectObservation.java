package com.louiekury.media.storage.application.model;

/** 체크섬은 저장소가 반환한 원문이며 복합 체크섬일 수 있습니다. 미제공 시 null입니다. */
public record ObjectObservation(long size, String contentType, String sha256) { }
