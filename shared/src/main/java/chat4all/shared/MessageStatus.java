package chat4all.shared;

/**
 * MessageStatus - State Machine for Message Lifecycle
 * 
 * PROPÓSITO EDUCACIONAL: Finite State Machine Pattern
 * ==================
 * 
 * MESSAGE LIFECYCLE:
 * ```
 * SENT → DELIVERED → READ
 *   ↓        ↓         ↓
 * (API)  (Connector) (Client)
 * ```
 * 
 * STATE TRANSITIONS:
 * - SENT → DELIVERED: Valid (connector confirms delivery)
 * - DELIVERED → READ: Valid (user marks as read)
 * - SENT → READ: INVALID (cannot read before delivery)
 * - READ → DELIVERED: INVALID (cannot go backwards)
 * - SENT → SENT: Valid (idempotent)
 * - DELIVERED → DELIVERED: Valid (idempotent)
 * - READ → READ: Valid (idempotent)
 * 
 * WHY STATE MACHINE?
 * - Prevents invalid transitions (data integrity)
 * - Documents business rules explicitly in code
 * - Enables validation before persistence
 * - Simplifies debugging (clear state at any point)
 * 
 * REAL-WORLD ANALOGY:
 * - Postal service: Letter sent → delivered → opened
 * - You can't "open" a letter before it's delivered
 * - You can't "unopen" a letter (irreversible)
 * 
 * @author Chat4All Educational Project
 */
public enum MessageStatus {
    
    /**
     * SENT: Message received by API, persisted in database
     * - Timestamp: created_at
     * - Next states: DELIVERED
     */
    SENT("SENT"),
    
    /**
     * DELIVERED: Connector confirmed message delivered to recipient
     * - Timestamp: delivered_at
     * - Next states: READ
     * - Example: WhatsApp shows single checkmark
     */
    DELIVERED("DELIVERED"),
    
    /**
     * READ: Recipient marked message as read
     * - Timestamp: read_at
     * - Next states: (final state)
     * - Example: WhatsApp shows double blue checkmarks
     */
    READ("READ");
    
    private final String value;
    
    /**
     * Constructor
     * 
     * @param value String representation of status
     */
    MessageStatus(String value) {
        this.value = value;
    }
    
    /**
     * Get string value
     * 
     * @return Status as string (e.g., "SENT", "DELIVERED", "READ")
     */
    public String getValue() {
        return value;
    }
    
    /**
     * Parse status from string
     * 
     * EXAMPLE:
     * ```java
     * MessageStatus status = MessageStatus.fromString("DELIVERED");
     * ```
     * 
     * @param value Status string
     * @return MessageStatus enum
     * @throws IllegalArgumentException if invalid status
     */
    public static MessageStatus fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Status cannot be null");
        }
        
        for (MessageStatus status : MessageStatus.values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        
        throw new IllegalArgumentException("Invalid status: " + value + 
            ". Valid values: SENT, DELIVERED, READ");
    }
    
    /**
     * Validate state transition
     * 
     * STATE TRANSITION TABLE:
     * ```
     * FROM       → TO         = VALID?
     * ────────────────────────────────
     * SENT       → SENT       = ✓ (idempotent)
     * SENT       → DELIVERED  = ✓ (normal flow)
     * SENT       → READ       = ✗ (skip delivery)
     * DELIVERED  → DELIVERED  = ✓ (idempotent)
     * DELIVERED  → READ       = ✓ (normal flow)
     * DELIVERED  → SENT       = ✗ (backwards)
     * READ       → READ       = ✓ (idempotent)
     * READ       → DELIVERED  = ✗ (backwards)
     * READ       → SENT       = ✗ (backwards)
     * ```
     * 
     * EXAMPLE USAGE:
     * ```java
     * MessageStatus current = MessageStatus.SENT;
     * MessageStatus next = MessageStatus.READ;
     * 
     * if (!MessageStatus.isValidTransition(current, next)) {
     *     throw new IllegalStateException("Cannot mark as READ before DELIVERED");
     * }
     * ```
     * 
     * @param from Current status
     * @param to Target status
     * @return true if transition is allowed, false otherwise
     */
    public static boolean isValidTransition(MessageStatus from, MessageStatus to) {
        if (from == null || to == null) {
            return false;
        }
        
        // Idempotency: same state is always valid
        if (from == to) {
            return true;
        }
        
        // Valid forward transitions
        switch (from) {
            case SENT:
                // SENT can only go to DELIVERED
                return to == DELIVERED;
                
            case DELIVERED:
                // DELIVERED can only go to READ
                return to == READ;
                
            case READ:
                // READ is final state (cannot transition further)
                return false;
                
            default:
                return false;
        }
    }
    
    /**
     * Check if status is final (no more transitions allowed)
     * 
     * @param status Status to check
     * @return true if READ (final state)
     */
    public static boolean isFinalState(MessageStatus status) {
        return status == READ;
    }
    
    /**
     * Get next valid statuses
     * 
     * EXAMPLE:
     * ```java
     * MessageStatus[] nextStates = MessageStatus.getNextValidStates(MessageStatus.SENT);
     * // Returns: [SENT, DELIVERED]
     * ```
     * 
     * @param current Current status
     * @return Array of valid next statuses
     */
    public static MessageStatus[] getNextValidStates(MessageStatus current) {
        switch (current) {
            case SENT:
                return new MessageStatus[] { SENT, DELIVERED };
            case DELIVERED:
                return new MessageStatus[] { DELIVERED, READ };
            case READ:
                return new MessageStatus[] { READ }; // Only idempotent
            default:
                return new MessageStatus[] {};
        }
    }
    
    @Override
    public String toString() {
        return value;
    }
}
