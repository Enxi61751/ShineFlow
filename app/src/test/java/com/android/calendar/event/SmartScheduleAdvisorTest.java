package com.android.calendar.event;

import junit.framework.TestCase;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public class SmartScheduleAdvisorTest extends TestCase {
    private Constructor<?> busyBlockConstructor;
    private Method findConflictsMethod;
    private Method overlapsMethod;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Class<?> busyBlockClass = Class.forName("com.android.calendar.event.SmartScheduleAdvisor$BusyBlock");
        busyBlockConstructor = busyBlockClass.getDeclaredConstructor(
                long.class,
                String.class,
                String.class,
                long.class,
                long.class,
                boolean.class
        );
        busyBlockConstructor.setAccessible(true);

        findConflictsMethod = SmartScheduleAdvisor.class.getDeclaredMethod(
                "findConflicts",
                List.class,
                long.class,
                long.class
        );
        findConflictsMethod.setAccessible(true);

        overlapsMethod = SmartScheduleAdvisor.class.getDeclaredMethod(
            "overlaps",
            long.class,
            long.class,
            long.class,
            long.class
        );
        overlapsMethod.setAccessible(true);
    }

        public void testOverlapsDetectsBoundaryCases() throws Exception {
        long start = 1_000L;
        long end = 2_000L;
        assertTrue(invokeOverlaps(1_500L, 2_500L, start, end));
        assertTrue(invokeOverlaps(1_200L, 1_800L, start, end));
        assertFalse(invokeOverlaps(100L, 999L, start, end));
        assertFalse(invokeOverlaps(2_000L, 3_000L, start, end));
    }

    public void testFindConflictsSortsByStartTime() throws Exception {
        long start = 1_000L;
        long end = 3_000L;
        List<Object> busyBlocks = Arrays.asList(
                newBusyBlock(10L, "Later", null, 2_200L, 2_800L, false),
                newBusyBlock(11L, "Earlier", null, 1_100L, 1_300L, false),
                newBusyBlock(12L, "Middle", null, 1_700L, 1_900L, false)
        );

        List<?> conflicts = invokeFindConflicts(busyBlocks, start, end);

        assertEquals(3, conflicts.size());
        assertEquals(11L, getFieldLong(conflicts.get(0), "eventId"));
        assertEquals(12L, getFieldLong(conflicts.get(1), "eventId"));
        assertEquals(10L, getFieldLong(conflicts.get(2), "eventId"));
    }

    private Object newBusyBlock(long eventId, String title, String location,
            long startMillis, long endMillis, boolean allDay) throws Exception {
        return busyBlockConstructor.newInstance(
                eventId,
                title,
                location,
                startMillis,
                endMillis,
                allDay
        );
    }

    private List<?> invokeFindConflicts(List<Object> busyBlocks, long startMillis,
            long endMillis) throws Exception {
        SmartScheduleAdvisor advisor = new SmartScheduleAdvisor();
        return (List<?>) findConflictsMethod.invoke(advisor, busyBlocks, startMillis, endMillis);
    }

    private boolean invokeOverlaps(long leftStart, long leftEnd, long rightStart,
            long rightEnd) throws Exception {
        SmartScheduleAdvisor advisor = new SmartScheduleAdvisor();
        return (Boolean) overlapsMethod.invoke(advisor, leftStart, leftEnd, rightStart, rightEnd);
    }

    private long getFieldLong(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getLong(target);
    }
}