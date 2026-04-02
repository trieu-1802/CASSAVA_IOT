package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.repositories.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public String register(User user) {
        if (userRepository.existsByUsername(user.getUsername())) {
            return "Lỗi: Username đã tồn tại!";
        }
        if (userRepository.existsByEmail(user.getEmail())) {
            return "Lỗi: Email đã tồn tại!";
        }

        // Mã hóa mật khẩu
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        userRepository.save(user);
        return "Đăng ký thành công!";
    }
    public Map<String, Object> login(String username, String password) {
        return userRepository.findByUsername(username)
                .map(user -> {
                    Map<String, Object> response = new HashMap<>();
                    if (passwordEncoder.matches(password, user.getPassword())) {
                        response.put("success", true);
                        response.put("username", user.getUsername());
                        // Tạm thời trả về username làm token nếu bạn chưa cài JWT
                        response.put("accessToken", "fake-jwt-token-for-" + user.getUsername());
                        response.put("isAdmin", user.isAdmin());
                        return response;
                    }
                    response.put("success", false);
                    response.put("message", "Sai mật khẩu!");
                    return response;
                })
                .orElseGet(() -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", false);
                    response.put("message", "Không tìm thấy người dùng!");
                    return response;
                });
    }
}