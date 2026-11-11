package com.ledgerline.money;

import java.util.List;

public record StatementResponse(Long accountId, long balancePaise, List<StatementLine> lines) {
}
