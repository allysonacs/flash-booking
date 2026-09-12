/**
 * Exceções de domínio e tratamento centralizado de erros.
 *
 * <p>Cada exceção de domínio mapeia para um status HTTP e um código de erro estável,
 * traduzidos em um único {@code @RestControllerAdvice} usando RFC 7807 (ProblemDetail).
 */
package com.example.flashbooking.exception;
