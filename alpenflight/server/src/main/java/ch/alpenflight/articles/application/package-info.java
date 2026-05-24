/**
 * Application layer for the articles module — orchestration service, DTOs,
 * and the mapper that translates the aggregate to wire shapes. No Spring
 * web concerns leak here; the controller lives in {@code articles.web}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.articles.application;
