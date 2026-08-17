package com.bookcase.catalog.dto;

/**
 * Владелец библиотеки — так, как он записан в проверенном токене.
 *
 * @param id идентификатор владельца, claim {@code sub}; единственное, чем размечаются все данные
 * @param username имя учётной записи, показывается человеку
 * @param email почта, если она указана при регистрации
 */
public record CurrentUser(String id, String username, String email) {}
