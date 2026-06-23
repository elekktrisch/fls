/**
 * Application layer for the email-templates module — the orchestration service,
 * wire DTOs, the file-default catalogue, and the mapper that translates the
 * aggregate to wire shapes. No Spring web concerns leak here; the controller
 * lives in {@code emailtemplates.web}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.emailtemplates.application;
