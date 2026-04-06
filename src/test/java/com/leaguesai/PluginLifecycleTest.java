package com.leaguesai;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;

/**
 * Lifecycle regression guards for {@link LeaguesAiPlugin}.
 *
 * <p>The full startUp/shutDown cycle cannot be exercised in unit tests because
 * it touches the live RuneLite client. These smoke tests pin the structural
 * invariants that previously broke during re-enable.
 */
public class PluginLifecycleTest {

    @Test
    public void testExecutorNotFinalField() throws Exception {
        Field field = LeaguesAiPlugin.class.getDeclaredField("llmExecutor");
        int modifiers = field.getModifiers();
        assertFalse("llmExecutor must not be final (re-enable would break)",
                Modifier.isFinal(modifiers));
        assertEquals("llmExecutor must be ExecutorService",
                ExecutorService.class, field.getType());
    }

    @Test
    public void testServiceFieldsAreVolatile() throws Exception {
        // The runtime services are mutated from background threads after
        // startUp() returns. They must be volatile so the EDT sees a
        // consistent reference.
        String[] volatileFields = {
                "taskRepo", "vectorIndex", "openAiClient",
                "chatService", "adviceService", "currentApiKey"
        };
        for (String name : volatileFields) {
            Field f = LeaguesAiPlugin.class.getDeclaredField(name);
            assertTrue(name + " must be volatile",
                    Modifier.isVolatile(f.getModifiers()));
        }
    }
}
