import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small HTTP/API layer that lets the browser use the railway data. */
public class RailwayWebServer {
    private static final int DEFAULT_PORT = 8080;
    private static final String DB_URL = "jdbc:sqlite:.data/railway.db";

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of(".data"));
        initializeDatabase();

        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", String.valueOf(DEFAULT_PORT)));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/trains", RailwayWebServer::handleTrains);
        server.createContext("/api/bookings", RailwayWebServer::handleBookings);
        server.createContext("/", RailwayWebServer::serveFrontend);
        server.setExecutor(null);
        server.start();

        System.out.println("Railway dashboard is running on port " + port);
    }

    private static Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(DB_URL);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private static void initializeDatabase() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS trains (
                        train_id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        arrival_time INTEGER NOT NULL,
                        departure_time INTEGER NOT NULL,
                        train_length INTEGER NOT NULL,
                        priority INTEGER NOT NULL,
                        total_seats INTEGER NOT NULL DEFAULT 10
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS passengers (
                        passenger_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reservations (
                        reservation_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        passenger_id INTEGER NOT NULL REFERENCES passengers(passenger_id),
                        train_id TEXT NOT NULL REFERENCES trains(train_id),
                        status TEXT NOT NULL CHECK(status IN ('CONFIRMED', 'WAITING', 'CANCELLED')),
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            try (ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM trains")) {
                if (count.next() && count.getInt(1) == 0) {
                    seedTrains(connection);
                }
            }
        }
    }

    private static void seedTrains(Connection connection) throws SQLException {
        String sql = "INSERT INTO trains(train_id, name, arrival_time, departure_time, train_length, priority) VALUES(?, ?, ?, ?, ?, ?)";
        Object[][] trains = {
                {"T101", "Kongu Express", 100, 130, 180, 2},
                {"T102", "Salem Express", 140, 170, 220, 3},
                {"T103", "Erode Passenger", 180, 210, 160, 1},
                {"T104", "Chennai Express", 220, 260, 300, 4},
                {"T105", "Coimbatore Special", 280, 310, 200, 2},
                {"T106", "Night Express", 330, 360, 250, 3}
        };
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Object[] train : trains) {
                insert.setString(1, (String) train[0]);
                insert.setString(2, (String) train[1]);
                insert.setInt(3, (Integer) train[2]);
                insert.setInt(4, (Integer) train[3]);
                insert.setInt(5, (Integer) train[4]);
                insert.setInt(6, (Integer) train[5]);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void handleTrains(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        String sql = """
                SELECT t.train_id, t.name, t.arrival_time, t.departure_time,
                       t.train_length, t.priority, t.total_seats,
                       SUM(CASE WHEN r.status = 'CONFIRMED' THEN 1 ELSE 0 END) AS booked
                FROM trains t LEFT JOIN reservations r ON r.train_id = t.train_id
                GROUP BY t.train_id ORDER BY t.arrival_time
                """;
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            List<String> trains = new ArrayList<>();
            while (rows.next()) {
                trains.add("{" +
                        "\"id\":" + quote(rows.getString("train_id")) + "," +
                        "\"name\":" + quote(rows.getString("name")) + "," +
                        "\"arrival\":" + rows.getInt("arrival_time") + "," +
                        "\"departure\":" + rows.getInt("departure_time") + "," +
                        "\"length\":" + rows.getInt("train_length") + "," +
                        "\"priority\":" + rows.getInt("priority") + "," +
                        "\"totalSeats\":" + rows.getInt("total_seats") + "," +
                        "\"booked\":" + rows.getInt("booked") + "}");
            }
            sendJson(exchange, 200, "[" + String.join(",", trains) + "]");
        } catch (SQLException exception) {
            sendError(exchange, exception);
        }
    }

    private static void handleBookings(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(exchange.getRequestMethod()) && "/api/bookings".equals(path)) {
            listBookings(exchange);
        } else if ("POST".equals(exchange.getRequestMethod()) && "/api/bookings".equals(path)) {
            createBooking(exchange);
        } else if ("DELETE".equals(exchange.getRequestMethod()) && path.matches("/api/bookings/\\d+")) {
            cancelBooking(exchange, Integer.parseInt(path.substring(path.lastIndexOf('/') + 1)));
        } else {
            sendJson(exchange, 405, "{\"error\":\"Unsupported booking request\"}");
        }
    }

    private static void listBookings(HttpExchange exchange) throws IOException {
        String sql = """
                SELECT r.reservation_id, p.name AS passenger, t.train_id, t.name AS train_name, r.status
                FROM reservations r JOIN passengers p ON p.passenger_id = r.passenger_id
                JOIN trains t ON t.train_id = r.train_id
                WHERE r.status <> 'CANCELLED' ORDER BY r.reservation_id DESC
                """;
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            List<String> bookings = new ArrayList<>();
            while (rows.next()) {
                bookings.add("{" +
                        "\"id\":" + rows.getInt("reservation_id") + "," +
                        "\"passenger\":" + quote(rows.getString("passenger")) + "," +
                        "\"trainId\":" + quote(rows.getString("train_id")) + "," +
                        "\"trainName\":" + quote(rows.getString("train_name")) + "," +
                        "\"status\":" + quote(rows.getString("status")) + "}");
            }
            sendJson(exchange, 200, "[" + String.join(",", bookings) + "]");
        } catch (SQLException exception) {
            sendError(exchange, exception);
        }
    }

    private static void createBooking(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseForm(exchange);
        String passengerName = form.getOrDefault("passengerName", "").trim();
        String trainId = form.getOrDefault("trainId", "").trim();
        if (passengerName.isBlank() || passengerName.length() > 80 || trainId.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"Enter a passenger name and choose a train\"}");
            return;
        }

        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                int capacity;
                try (PreparedStatement findTrain = connection.prepareStatement("SELECT total_seats FROM trains WHERE train_id = ?")) {
                    findTrain.setString(1, trainId);
                    try (ResultSet result = findTrain.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            sendJson(exchange, 404, "{\"error\":\"Train not found\"}");
                            return;
                        }
                        capacity = result.getInt(1);
                    }
                }
                int confirmed;
                try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM reservations WHERE train_id = ? AND status = 'CONFIRMED'")) {
                    count.setString(1, trainId);
                    try (ResultSet result = count.executeQuery()) { result.next(); confirmed = result.getInt(1); }
                }
                long passengerId;
                try (PreparedStatement insertPassenger = connection.prepareStatement("INSERT INTO passengers(name) VALUES(?)", Statement.RETURN_GENERATED_KEYS)) {
                    insertPassenger.setString(1, passengerName);
                    insertPassenger.executeUpdate();
                    try (ResultSet keys = insertPassenger.getGeneratedKeys()) { keys.next(); passengerId = keys.getLong(1); }
                }
                String status = confirmed < capacity ? "CONFIRMED" : "WAITING";
                int reservationId;
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO reservations(passenger_id, train_id, status) VALUES(?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    insert.setLong(1, passengerId);
                    insert.setString(2, trainId);
                    insert.setString(3, status);
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) { keys.next(); reservationId = keys.getInt(1); }
                }
                connection.commit();
                sendJson(exchange, 201, "{\"id\":" + reservationId + ",\"status\":" + quote(status) + "}");
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            sendError(exchange, exception);
        }
    }

    private static void cancelBooking(HttpExchange exchange, int reservationId) throws IOException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                String trainId = null;
                String cancelledStatus = null;
                try (PreparedStatement find = connection.prepareStatement("SELECT train_id, status FROM reservations WHERE reservation_id = ? AND status <> 'CANCELLED'")) {
                    find.setInt(1, reservationId);
                    try (ResultSet result = find.executeQuery()) {
                        if (result.next()) {
                            trainId = result.getString("train_id");
                            cancelledStatus = result.getString("status");
                        }
                    }
                }
                if (trainId == null) {
                    connection.rollback();
                    sendJson(exchange, 404, "{\"error\":\"Active reservation not found\"}");
                    return;
                }
                try (PreparedStatement cancel = connection.prepareStatement("UPDATE reservations SET status = 'CANCELLED' WHERE reservation_id = ?")) {
                    cancel.setInt(1, reservationId);
                    cancel.executeUpdate();
                }
                if ("CONFIRMED".equals(cancelledStatus)) {
                    try (PreparedStatement promote = connection.prepareStatement("UPDATE reservations SET status = 'CONFIRMED' WHERE reservation_id = (SELECT reservation_id FROM reservations WHERE train_id = ? AND status = 'WAITING' ORDER BY reservation_id LIMIT 1)")) {
                        promote.setString(1, trainId);
                        promote.executeUpdate();
                    }
                }
                connection.commit();
                sendJson(exchange, 200, "{\"message\":\"Booking cancelled\"}");
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            sendError(exchange, exception);
        }
    }

    private static void serveFrontend(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        String requested = exchange.getRequestURI().getPath();
        if ("/".equals(requested)) requested = "/index.html";
        if (requested.contains("..") || requested.contains("\\")) {
            sendJson(exchange, 400, "{\"error\":\"Invalid path\"}");
            return;
        }
        String resource = "/web" + requested;
        try (var input = RailwayWebServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                sendJson(exchange, 404, "{\"error\":\"Page not found\"}");
                return;
            }
            byte[] bytes = input.readAllBytes();
            String contentType = requested.endsWith(".css") ? "text/css; charset=utf-8"
                    : requested.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : "text/html; charset=utf-8";
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    private static Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> values = new LinkedHashMap<>();
        for (String item : body.split("&")) {
            String[] pair = item.split("=", 2);
            if (pair.length == 2) {
                values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static String quote(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> { if (ch < 0x20) json.append(String.format("\\u%04x", (int) ch)); else json.append(ch); }
            }
        }
        return json.append('"').toString();
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sendError(HttpExchange exchange, SQLException exception) throws IOException {
        System.err.println("Database error: " + exception.getMessage());
        sendJson(exchange, 500, "{\"error\":\"Database request failed\"}");
    }
}
