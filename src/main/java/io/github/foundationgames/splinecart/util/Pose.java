package io.github.foundationgames.splinecart.util;

import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public record Pose(Vector3dc translation, Matrix3dc basis) {
    public void interpolate(Pose other, double t, Vector3d translation, Matrix3d basis, Vector3d gradient) {
        double factor = this.translation().distance(other.translation());
        interpolate(other, t, factor, translation, basis, gradient);
    }

    public void interpolate(Pose other, double t, double factor, Vector3d translation, Matrix3d basis, Vector3d gradient) {
        var point0 = translation.set(this.translation());
        var point1 = new Vector3d(other.translation());

        var grad0 = new Vector3d(0, 0, 1).mul(this.basis());
        var grad1 = new Vector3d(0, 0, 1).mul(other.basis());

        cubicHermiteSpline(t, factor, point0, grad0, point1, grad1, translation, gradient);
        var ngrad = gradient.normalize(new Vector3d());

        var rot0 = this.basis().getNormalizedRotation(new Quaterniond());
        var rot1 = other.basis().getNormalizedRotation(new Quaterniond());

        var rotT = rot0.nlerp(rot1, t, new Quaterniond());
        basis.set(rotT);

        var basisGrad = new Vector3d(0, 0, 1).mul(basis);
        var axis = ngrad.cross(basisGrad, new Vector3d());

        if (axis.length() > 0) {
            axis.normalize();
            double angleToNewBasis = basisGrad.angleSigned(ngrad, axis);
            if (angleToNewBasis != 0) {
                new Matrix3d().identity().rotate(angleToNewBasis, axis)
                        .mul(basis, basis).normal();
            }
        }
    }

    public static void cubicHermiteSpline(double t, double factor, Vector3dc clientPos, Vector3dc clientVelocity, Vector3dc serverPos, Vector3dc serverVelocity,
                                          Vector3d newClientPos, Vector3d newClientVelocity) {
        var temp = new Vector3d();
        var diff = new Vector3d(serverPos).sub(clientPos);

        newClientVelocity.set(temp.set(diff).mul(6*t - 6*t*t))
                .add(temp.set(clientVelocity).mul(3*t*t - 4*t + 1).mul(factor))
                .add(temp.set(serverVelocity).mul(3*t*t - 2*t).mul(factor));

        newClientPos.set(clientPos)
                .add(temp.set(clientVelocity).mul(t*t*t - 2*t*t + t).mul(factor))
                .add(temp.set(diff).mul(-2*t*t*t + 3*t*t))
                .add(temp.set(serverVelocity).mul(t*t*t - t*t).mul(factor));
    }
}
