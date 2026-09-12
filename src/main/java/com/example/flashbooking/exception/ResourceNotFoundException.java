package com.example.flashbooking.exception;

/**
 * Recurso inexistente. Existe como base porque agora há mais de um tipo de "não encontrado";
 * o tratamento HTTP é único e reage a esta classe, não a cada subtipo.
 */
public abstract class ResourceNotFoundException extends DomainException {

    protected ResourceNotFoundException(String errorCode, String message) {
        super(errorCode, message);
    }
}
