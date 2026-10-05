package dev.nordfjell.releasechecks;

import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

/** Runs the existing main-based regression suite inside Maven with assertions enabled. */
final class MainRegressionTest {
    @Test
    void regression1() throws Throwable {
        try {
            Class.forName("com.nordfjell.nordqueue.BanProtocolTest").getMethod("main", String[].class)
                    .invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @Test
    void regression2() throws Throwable {
        try {
            Class.forName("com.nordfjell.nordqueue.BanStorageTest").getMethod("main", String[].class)
                    .invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @Test
    void regression3() throws Throwable {
        try {
            Class.forName("com.nordfjell.nordqueue.QueueStateTest").getMethod("main", String[].class)
                    .invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
