package com.neurovault.backend.dto;

import com.neurovault.backend.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

/**
 * DTO representing user info returned safely from APIs.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDto {
    private UUID id;
    private String username;
    private String email;
    private User.Role role;
}
