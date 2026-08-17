package com.bookcase.ingester.exception;

/** Задачи нет — или она есть, но чужая. Разницы снаружи быть не должно. */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String taskId) {
        super("задача " + taskId + " не найдена");
    }
}
