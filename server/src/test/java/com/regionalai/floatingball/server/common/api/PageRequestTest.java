package com.regionalai.floatingball.server.common.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageRequestTest {

    @Test
    void shouldNormalizeCurrentAndSize() {
        PageRequest request = PageRequest.of(0L, -1L);

        assertEquals(1L, request.getCurrent());
        assertEquals(10L, request.getSize());
    }

    @Test
    void shouldCapPageSizeAtOneHundred() {
        PageRequest request = PageRequest.of(3L, 500L);

        assertEquals(3L, request.getCurrent());
        assertEquals(100L, request.getSize());
        assertEquals(200L, request.getOffset());
    }

    @Test
    void shouldAvoidOffsetOverflowAndClampIndexes() {
        PageRequest request = PageRequest.of(Long.MAX_VALUE, 100L);

        assertEquals(Long.MAX_VALUE, request.getOffset());
        assertEquals(39, request.fromIndex(39));
        assertEquals(39, request.toIndex(39));
    }

    @Test
    void pageResponseShouldExposeEmptyRecordsInsteadOfNull() {
        PageResponse<String> response = new PageResponse<String>(1L, 10L, 0L, null);

        assertEquals(0, response.getRecords().size());
    }
}
