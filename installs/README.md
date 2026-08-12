# Carvel PackageInstall

`eventim-booking-engine.yml` installs version `0.1.0` of the local Eventim
package. The setup script renders immutable application image values into a
Secret, creates the service account/RBAC, applies the versioned Package
resources, and triggers PackageInstall reconciliation.
