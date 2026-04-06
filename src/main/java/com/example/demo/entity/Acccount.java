package com.example.demo.entity;

import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Entity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Data
public class Acccount {
    private Long id;
    private String email;
    private String password;
    private String userName;
    private Long ID;
    private boolean admin;
}
