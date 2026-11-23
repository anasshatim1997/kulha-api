package ma.kulha.admin.support.interfaces.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AssignSupportCaseRequest(@NotNull UUID assignedTo) {
}
