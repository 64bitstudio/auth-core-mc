package com.mcortes.authcoremc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An end user belonging to a tenant. Registration allows either email or
 * phone (at least one is required) — enforced both here (fail fast, in
 * memory, before ever reaching the database) and by a CHECK constraint at
 * the database level (defense in depth: nothing bypasses this rule by
 * writing SQL directly, a migration mistake, or a future caller of the
 * repository that skips this constructor).
 */
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column
    private String email;

    @Column
    private String phone;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private String apellidos;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified;

    @Column(name = "totp_secret_encrypted")
    private String totpSecretEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
        // JPA
    }

    public User(Tenant tenant, String email, String phone, String nombre, String apellidos, String passwordHash) {
        if ((email == null || email.isBlank()) && (phone == null || phone.isBlank())) {
            throw new IllegalArgumentException("A user must have at least an email or a phone number");
        }
        this.tenant = tenant;
        this.email = email;
        this.phone = phone;
        this.nombre = nombre;
        this.apellidos = apellidos;
        this.passwordHash = passwordHash;
        this.emailVerified = false;
        this.phoneVerified = false;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getNombre() {
        return nombre;
    }

    public String getApellidos() {
        return apellidos;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public boolean isPhoneVerified() {
        return phoneVerified;
    }

    public String getTotpSecretEncrypted() {
        return totpSecretEncrypted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void markEmailVerified() {
        this.emailVerified = true;
    }

    public void markPhoneVerified() {
        this.phoneVerified = true;
    }

    /**
     * Changes the user's email as part of the change-email flow (ticket 003)
     * — only called after the confirmation link sent to {@code newEmail} has
     * been used, so the new address is verified by construction.
     */
    public void changeEmail(String newEmail) {
        if (newEmail == null || newEmail.isBlank()) {
            throw new IllegalArgumentException("newEmail must not be blank");
        }
        this.email = newEmail;
        this.emailVerified = true;
    }
}
