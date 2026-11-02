package com.transport.autopartage.vehiculeRoute;

abstract class VehiculeException extends RuntimeException {
    private VehiculeException(String message, Throwable cause) {
        super(message, cause);
    }

    static final class DuplicateRegistration extends VehiculeException {
        DuplicateRegistration() {
            this(null);
        }

        DuplicateRegistration(Throwable cause) {
            super("A vehicle with this registration number already exists.", cause);
        }
    }

    static final class UnknownCity extends VehiculeException {
        UnknownCity(String nom) {
            super("Unknown city: " + nom, null);
        }
    }

    static final class UnknownCategory extends VehiculeException {
        UnknownCategory(String nom) {
            super("Unknown category: " + nom, null);
        }
    }

    static final class NotFound extends VehiculeException {
        NotFound(long vehiculeId) {
            super("Vehicle " + vehiculeId + " was not found.", null);
        }
    }

    static final class OwnershipRequired extends VehiculeException {
        OwnershipRequired() {
            super("You do not own this vehicle.", null);
        }
    }

    static final class InvalidCaller extends VehiculeException {
        InvalidCaller() {
            super("A single positive X-User-Id is required.", null);
        }
    }
}
