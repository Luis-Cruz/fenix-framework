package pt.ist.fenixframework.backend.mem;

import java.util.concurrent.Callable;

import pt.ist.fenixframework.Transaction;
import pt.ist.fenixframework.TransactionManager;

public class NoOpTransactionManager implements TransactionManager {
    @Override
    public void begin() {}

    @Override
    public void begin(boolean readOnly) {}

    @Override
    public void commit() {}

    @Override
    public Transaction getTransaction() { return null; }

    @Override
    public void rollback() {}

    @Override
    public <T> T withTransaction(Callable<T> command) {
        try {
            return command.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

