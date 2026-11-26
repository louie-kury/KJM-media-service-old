package com.louiekury.media.upload.application.model;

import com.louiekury.media.validation.domain.MediaType;

/** 저장 요청 전 검증된 복구 참고 정보입니다. */
public record RecoveryMetadata(String filename, MediaType type, long size, String sha256) { }
