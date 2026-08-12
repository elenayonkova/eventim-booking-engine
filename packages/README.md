# Carvel package

`eventim-booking-engine/config` contains the ytt application templates and
`kbld.yml` contains local Docker build sources. `package-template.yml` embeds
the templates into a versioned Carvel `Package` so a `PackageInstall` can
reconcile the application without requiring a registry for the package bundle.

`scripts/setup.sh` renders and applies the package automatically.
