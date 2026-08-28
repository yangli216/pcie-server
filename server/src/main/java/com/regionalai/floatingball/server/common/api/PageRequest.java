package com.regionalai.floatingball.server.common.api;

public final class PageRequest {

    public static final long DEFAULT_CURRENT = 1L;
    public static final long DEFAULT_SIZE = 10L;
    public static final long MAX_SIZE = 100L;

    private final long current;
    private final long size;

    private PageRequest(long current, long size) {
        this.current = current;
        this.size = size;
    }

    public static PageRequest of(long current, long size) {
        long normalizedCurrent = Math.max(DEFAULT_CURRENT, current);
        long normalizedSize = size <= 0L ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new PageRequest(normalizedCurrent, normalizedSize);
    }

    public static PageRequest of(Number current, Number size) {
        long requestedCurrent = current == null ? DEFAULT_CURRENT : current.longValue();
        long requestedSize = size == null ? DEFAULT_SIZE : size.longValue();
        return of(requestedCurrent, requestedSize);
    }

    public long getCurrent() {
        return current;
    }

    public long getSize() {
        return size;
    }

    public long getOffset() {
        long pageIndex = current - 1L;
        if (pageIndex > Long.MAX_VALUE / size) {
            return Long.MAX_VALUE;
        }
        return pageIndex * size;
    }

    public int fromIndex(int total) {
        int safeTotal = Math.max(0, total);
        return (int) Math.min(getOffset(), safeTotal);
    }

    public int toIndex(int total) {
        int safeTotal = Math.max(0, total);
        long from = fromIndex(safeTotal);
        return (int) Math.min(from + size, safeTotal);
    }
}
