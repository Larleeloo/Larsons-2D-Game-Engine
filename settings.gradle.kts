rootProject.name = "Larsons-2D-Game-Engine"

// The GL backend, in its own project so it cannot contaminate the core's
// zero-dependency guarantee (invariant 1). `:gl` depends on the root; the root
// depends on nothing. A player with a bare JRE and no `:gl` still gets a
// working game, and there is no import anyone can add to the core that would
// change that — `ModuleBoundaryTest` fails the build on the attempt.
//
// The core stays at the root rather than moving to `:engine`: the plan called
// that once and does not revisit it. Moving it would rewrite every path in
// every test, every report and every line of this document that names a file,
// to gain a symmetry nothing needs.
include("gl")
