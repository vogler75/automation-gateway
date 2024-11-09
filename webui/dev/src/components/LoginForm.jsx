import React, { useState, useContext, createContext } from "react";
import { getToken } from "../api/getToken";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../utils/auth";
import { AlertDismissible } from "./AlertDismissible";

const MyTokenContext = createContext();

export function LoginForm() {
  const navigate = useNavigate();
  const [token, setToken] = useState("");
  const auth = useAuth();
  const [user, setUser] = useState("");
  const [showAlert, setShowAlert] = useState(false);
  console.log(showAlert);

  async function onClick() {
    const response = await getToken();
    if (user !== "") {
      if (response.data.createAccessToken.ok === true) {
        setToken(response.data.createAccessToken.message);
        console.log(response.data.createAccessToken.message);
        auth.tokenActive(
          JSON.stringify(response.data.createAccessToken.message)
        );
        auth.login(user);
        navigate("WBM", { replace: true });
      } else {
        setShowAlert(true);
        console.log("You are not allowed!");
      }
    } else {
      setShowAlert(true);
      console.log("Enter a username!");
    }
  }

  const closeAlert = () => {
    setShowAlert(false);
  };

  const onSubmit = async (e) => {
    e.preventDefault();
    await onClick();
  };

  return (
    <MyTokenContext.Provider value={{ token, setToken }}>
      <>
        <form onSubmit={onSubmit}>
          <div
            style={{
              textAlign: "center",
              fontSize: "2rem",
              fontWeight: "bold",
              margin: "20px 0",
            }}
          >
            <label>Login information</label>
          </div>
          <br />
          <div className="mb-3">
            <input
              type="email"
              className="form-control"
              id="exampleInputUser"
              aria-describedby="emailHelp"
              placeholder="Username"
              onChange={(e) => setUser(e.target.value)}
            />
          </div>
          <div className="mb-3">
            <input
              type="password"
              className="form-control"
              id="exampleInputPassword"
              placeholder="Password"
            />
          </div>

          <button type="submit" className="btn btn-success" onClick={onClick}>
            Login
          </button>
        </form>
        <AlertDismissible show={showAlert} onClose={closeAlert} />
      </>
    </MyTokenContext.Provider>
  );
}
export const useMyToken = () => {
  return useContext(MyTokenContext);
};

export default MyTokenContext;
