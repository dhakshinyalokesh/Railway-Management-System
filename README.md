# Railway Management System

This repository began as a Java console simulation. The web demo adds a browser dashboard and an SQLite database while keeping the original console classes in place.

## What the web demo does

- Shows the six sample train services and seat availability.
- Saves passengers and reservations in SQLite, so data remains after the app closes.
- Adds a passenger to the FIFO waiting list when a train has no confirmed seats left.
- Cancels reservations and promotes the oldest waiting passenger when a confirmed seat becomes free.
- Calculates compatible platform assignments from the saved timetable and shows platform lengths, time windows, and clearance buffers in the dashboard.

## Run the web demo

You need JDK 21 and Apache Maven. The `run-web.bat` file starts the app from Windows. Maven downloads the SQLite JDBC driver the first time it runs.

```text
run-web.bat
```

Then open `http://localhost:8080` in a browser. Stop the server with Ctrl+C in the terminal. The database is stored at `.data/railway.db`; that local data folder is excluded from Git.

## How the pieces fit together

```text
Browser (HTML, CSS, JavaScript)
          │ HTTP requests / JSON responses
          ▼
RailwayWebServer.java (Java API and booking rules)
          │ JDBC (Java's database connection interface)
          ▼
SQLite file: .data/railway.db
```

The browser asks `GET /api/trains`, `GET /api/bookings`, and `GET /api/platforms` for data. Booking uses `POST /api/bookings`; cancellation uses `DELETE /api/bookings/{id}`. The Java server validates these requests, runs SQL statements, and sends the result back as JSON. The platform endpoint reads the saved train schedule and recalculates assignments using the same `SimplePlatformAllocationPolicy`, `Platform`, `PlatformOccupation`, and `PlatformRequest` classes as the console simulation. It uses three sample platforms (200 m, 280 m, and 350 m) with a 10-minute clearance buffer. The current web board recalculates the schedule on refresh; platform assignments themselves are not stored as database records.

The database has three related tables:

- `trains` stores each train and its seat capacity.
- `passengers` stores each passenger once for each booking.
- `reservations` links a passenger to a train and tracks `CONFIRMED`, `WAITING`, or `CANCELLED` status.

The original console entry point is still `RailwaySystem.java`. Start the browser demo with `run-web.bat` instead.

## Assignment report and scenario checks

See docs/REPORT.md for the problem analysis, UML, data-structure justification, scenario results, demo flow, and limitations. The 30 sample booking requests are in docs/sample-booking-requests.csv. Compile and run the dependency-free assignment checks with:

```powershell
javac -d target\check *.java
java -cp target\check AssignmentScenarioChecks
```

