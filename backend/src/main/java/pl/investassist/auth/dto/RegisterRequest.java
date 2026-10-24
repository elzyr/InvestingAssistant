package pl.investassist.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import pl.investassist.common.enums.Currency;

public record RegisterRequest(
    @NotBlank(message = "Email cannot be blank")
        @Email(message = "Invalid email format")
        @Size(max = 255, message = "Email cannot exceed 255 characters")
        String email,
    @NotBlank(message = "Password cannot be blank")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters long")
        String password,
    @NotBlank(message = "First name cannot be blank")
        @Size(max = 255, message = "First name cannot exceed 255 characters")
        String firstName,
    @NotBlank(message = "Last name cannot be blank")
        @Size(max = 255, message = "Last name cannot exceed 255 characters")
        String lastName,
    Currency baseCurrency) {}
