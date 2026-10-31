package ma.kulha.merchant.application;

import java.util.List;

public record PageResult<T>(List<T> items, long total) {
}
