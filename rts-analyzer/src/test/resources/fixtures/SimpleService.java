package com.example.service;

import com.example.repository.UserRepository;
import com.example.model.User;

/**
 * Simple service for testing AST analysis.
 */
public class SimpleService {

    private final UserRepository repository;

    public SimpleService(UserRepository repository) {
        this.repository = repository;
    }

    public User findUser(String id) {
        return repository.findById(id);
    }

    public void saveUser(User user) {
        repository.save(user);
    }
}
