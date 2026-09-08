/**
 * Full-page loading spinner shown during Keycloak initialization.
 * Matches the Karyo brand with blue-600 primary color.
 */
export function AuthLoading() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-background">
      <div className="flex flex-col items-center gap-4">
        {/* Simple text-based Karyo logo matching the Keycloak theme */}
        <div className="text-4xl font-bold tracking-tight text-primary">
          Karyo
          <span className="font-light text-muted-foreground"> WMS</span>
        </div>
        {/* Subtle pulse animation */}
        <div className="flex items-center gap-2">
          <div className="h-2 w-2 animate-pulse rounded-full bg-primary" />
          <div
            className="h-2 w-2 animate-pulse rounded-full bg-primary"
            style={{ animationDelay: '150ms' }}
          />
          <div
            className="h-2 w-2 animate-pulse rounded-full bg-primary"
            style={{ animationDelay: '300ms' }}
          />
        </div>
        <p className="text-sm text-muted-foreground">Loading Karyo WMS...</p>
      </div>
    </div>
  );
}
