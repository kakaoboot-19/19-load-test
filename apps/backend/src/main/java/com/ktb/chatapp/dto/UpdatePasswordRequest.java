package com.ktb.chatapp.dto;

import com.ktb.chatapp.validation.ValidName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.Getter;

@Data
@Getter
public class UpdatePasswordRequest {

//    @NotBlank(message = "현재 비밀번호를 입력해주세요.")
//    private String currentPassword;

    @NotBlank(message = "새 비밀번호를 입력해주세요.")
    private String newPassword;
}

