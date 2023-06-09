package com.lian.notabackdoor.pages;

import com.lian.notabackdoor.NotABackdoor;
import com.lian.notabackdoor.Utils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class LostPageHandler implements HttpHandler {

    private static final String PAGE_PATH = "/lost";

    public void handle(HttpExchange exchange) throws IOException {

        String requestMethod = exchange.getRequestMethod();
        if (requestMethod.equalsIgnoreCase("GET")) {

            String requestedPath = exchange.getRequestURI().getPath();
            if (!requestedPath.equals(PAGE_PATH)) {
                Utils.redirectToLostPage(exchange);
                return;
            }
            DisplayLostPage(exchange);
        } else {
            new ErrorPageHandler().DisplayErrorPage(exchange, "405 Method Not Allowed", "Only GET requests are allowed on this resource. Please try a GET request or contact the server administrator for assistance.", 405);
        }


    }
    public void DisplayLostPage(HttpExchange exchange) throws IOException {
        Utils.generateFiles();
        File file = new File(NotABackdoor.getPlugin(NotABackdoor.class).getDataFolder(), "pages/lost.html");

        String response = new String(Files.readAllBytes(file.toPath()));
        exchange.sendResponseHeaders(404, response.length());
        exchange.getResponseBody().write(response.getBytes());
        exchange.getResponseBody().close();
    }
}
