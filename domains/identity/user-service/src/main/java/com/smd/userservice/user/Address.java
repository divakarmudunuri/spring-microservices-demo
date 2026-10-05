package com.smd.userservice.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "addresses")
public class Address {

    @Id
    private UUID id;

    private UUID userId;

    /** The recipient's name. */
    private String fullName;

    private String line1;

    private String line2;

    private String city;

    private String state;

    private String postalCode;

    /** ISO 3166-1 alpha-2. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 2)
    private String country;

    private String phone;

    private boolean isDefault;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected Address() {
        // for JPA
    }

    public static Address newDefault(UUID userId) {
        Address a = new Address();
        a.id = UUID.randomUUID();
        a.userId = userId;
        a.isDefault = true;
        return a;
    }

    public void update(String fullName, String line1, String line2, String city, String state, String postalCode,
                       String country, String phone) {
        this.fullName = fullName;
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.state = state;
        this.postalCode = postalCode;
        this.country = country.toUpperCase();
        this.phone = phone;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getLine1() {
        return line1;
    }

    public String getLine2() {
        return line2;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountry() {
        return country;
    }

    public String getPhone() {
        return phone;
    }

    public boolean isDefault() {
        return isDefault;
    }
}
