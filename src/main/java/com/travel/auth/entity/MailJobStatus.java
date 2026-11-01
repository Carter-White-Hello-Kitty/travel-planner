package com.travel.auth.entity;

/** Lifecycle state of a password-reset mail job. */
public enum MailJobStatus {
  PENDING,
  READY,
  SENT,
  FAILED
}
