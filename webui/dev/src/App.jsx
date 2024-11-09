import "./App.css";
import { AlertDismissible } from "./components/AlertDismissible";
import { LoginForm } from "./components/LoginForm";
import { MainLogo } from "./components/MainLogo";
import { Container, Row, Col } from "react-bootstrap";

function App() {
  return (
    <>
      <Container fluid className="loginRows">
        <Col className="loginCols">
          <div>
            <MainLogo />
          </div>
        </Col>
        <Col
          className="loginCols"
          style={{
            backgroundColor: "#EDEBED",
          }}
        >
          <div
            style={{
              width: "50%",
              height: "100vh",
              display: "flex",
              flexDirection: "column",
              justifyContent: "center",
            }}
          >
            <LoginForm />
            <AlertDismissible />
          </div>
        </Col>
      </Container>
    </>
  );
}

export default App;
