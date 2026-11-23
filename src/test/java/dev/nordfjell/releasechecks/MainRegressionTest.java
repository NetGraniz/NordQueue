package dev.nordfjell.releasechecks;

import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

/** Runs the existing main-based regression suite inside Maven with assertions enabled. */
final class MainRegressionTest {
    @Test
    void queueStateRegression() throws Throwable {
        try {
            Class.forName("com.nordfjell.nordqueue.QueueStateTest").getMethod("main", String[].class)
                    .invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
