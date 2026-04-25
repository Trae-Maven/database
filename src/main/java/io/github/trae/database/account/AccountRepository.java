package io.github.trae.database.account;

import io.github.trae.database.account.property.AccountProperty;
import io.github.trae.database.driver.DatabaseDriver;
import io.github.trae.database.filter.Filter;
import io.github.trae.database.repository.AbstractRepository;
import io.github.trae.database.repository.annotations.Repository;

import java.util.List;

@Repository(databaseName = "Admin", collectionName = "Accounts")
public class AccountRepository extends AbstractRepository<Account, AccountProperty> {

    public AccountRepository(final DatabaseDriver databaseDriver) {
        super(databaseDriver);
    }

    @Override
    public void registerIndexes() {
    }

    @Override
    public List<Filter> getFiltersByDomain(final Account account) {
        return List.of(Filter.equals(AccountProperty.EMAIL.name(), account.getEmail()));
    }
}