package tachiyomi.domain.translation.validation

object PreferenceValidator {
    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val errorMessage: String, val errorDetail: String = "") : ValidationResult()
        data class Warning(val message: String, val detail: String = "") : ValidationResult()
    }

    fun validateTemperature(value: String): ValidationResult {
        val floatValue = value.toFloatOrNull()
        return when {
            value.isBlank() -> ValidationResult.Invalid("Temperature cannot be empty", "Enter 0.0-2.0")
            floatValue == null -> ValidationResult.Invalid("Invalid format", "Must be a number")
            floatValue < 0f -> ValidationResult.Invalid("Too low", "Cannot be negative")
            floatValue > 2.0f -> ValidationResult.Warning("High value", "Above 2.0 may be inconsistent")
            else -> ValidationResult.Valid
        }
    }

    fun validateMaxTokens(value: String): ValidationResult {
        val intValue = value.toIntOrNull()
        return when {
            value.isBlank() -> ValidationResult.Invalid("Max tokens cannot be empty", "Enter a positive number")
            intValue == null -> ValidationResult.Invalid("Invalid format", "Must be a whole number")
            intValue <= 0 -> ValidationResult.Invalid("Too low", "Must be positive")
            intValue > 32768 -> ValidationResult.Warning("High value", "May cause memory issues")
            else -> ValidationResult.Valid
        }
    }

    fun validateApiKey(value: String, engine: String): ValidationResult {
        return when {
            value.isBlank() -> ValidationResult.Invalid("API key cannot be empty", "Enter a valid API key")
            value.length < 10 -> ValidationResult.Warning("Short key", "API key seems too short")
            value.length > 5000 -> ValidationResult.Invalid("Too long", "API key exceeds maximum length")
            engine.equals("MLKIT", ignoreCase = true) || engine.equals("GOOGLE", ignoreCase = true) -> ValidationResult.Warning("Not required", "This engine does not require an API key")
            else -> ValidationResult.Valid
        }
    }

    fun sanitizeModelName(value: String): String {
        return value.trim()
            .replace(Regex("[<>\"'&]"), "")
            .replace(Regex("\\s+"), " ")
            .take(100)
    }
}
