package io.github.trae.database.account;

import io.github.trae.database.account.property.AccountProperty;
import io.github.trae.database.domain.data.DomainData;
import io.github.trae.database.domain.models.Domain;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@RequiredArgsConstructor
@Getter
@Setter
public class Account implements Domain<AccountProperty> {

    private final UUID id;

    private String email, username, password;

    public Account(final DomainData<AccountProperty> domainData) {
        this(domainData.getIdentifier());

        this.email = domainData.get(String.class, AccountProperty.EMAIL);
        this.username = domainData.get(String.class, AccountProperty.USERNAME);
        this.password = domainData.get(String.class, AccountProperty.PASSWORD);
    }

    @Override
    public Object getValueByProperty(final AccountProperty accountProperty) {
        return switch (accountProperty) {
            case EMAIL -> this.getEmail();
            case USERNAME -> this.getUsername();
            case PASSWORD -> this.getPassword();
        };
    }
}