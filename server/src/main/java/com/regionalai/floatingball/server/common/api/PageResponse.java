package com.regionalai.floatingball.server.common.api;

import java.util.List;
import java.util.Collections;

public class PageResponse<T> {

    private long current;
    private long size;
    private long total;
    private List<T> records;

    public PageResponse() {
    }

    public PageResponse(long current, long size, long total, List<T> records) {
        this.current = current;
        this.size = size;
        this.total = total;
        this.records = records == null ? Collections.<T>emptyList() : records;
    }

    public long getCurrent() {
        return current;
    }

    public void setCurrent(long current) {
        this.current = current;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public List<T> getRecords() {
        return records;
    }

    public void setRecords(List<T> records) {
        this.records = records == null ? Collections.<T>emptyList() : records;
    }
}
