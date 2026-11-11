package com.ledgerline.audit;

public record ChainBreak(long accountId, long entryId, String problem, String expectedHash, String storedHash) {
}
